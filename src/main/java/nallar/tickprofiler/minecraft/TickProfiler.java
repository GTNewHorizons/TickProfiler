package nallar.tickprofiler.minecraft;

import com.google.common.base.Charsets;
import com.google.common.collect.MapMaker;
import com.google.common.io.Files;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import nallar.tickprofiler.Log;
import nallar.tickprofiler.minecraft.commands.Command;
import nallar.tickprofiler.minecraft.commands.DumpCommand;
import nallar.tickprofiler.minecraft.commands.ProfileCommand;
import nallar.tickprofiler.minecraft.commands.TPSCommand;
import nallar.tickprofiler.minecraft.profiling.EntityTickProfiler;
import nallar.tickprofiler.minecraft.profiling.LagSpikeProfiler;
import nallar.tickprofiler.reporting.Metrics;
import nallar.tickprofiler.util.TableFormatter;
import nallar.tickprofiler.util.VersionUtil;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.io.*;
import java.util.*;

@SuppressWarnings("WeakerAccess")
@Mod(modid = "TickProfiler", name = "TickProfiler", acceptableRemoteVersions = "*")
public class TickProfiler {
	private static final Set<World> profilingWorlds = Collections.newSetFromMap(new MapMaker().weakKeys().<World, Boolean>makeMap());
	@Mod.Instance("TickProfiler")
	public static TickProfiler instance;
	public static long tickTime = 20; // Initialise with non-zero value to avoid divide-by-zero errors calculating TPS
	public static long lastTickTime;

	static {
		new Metrics("TickProfiler", VersionUtil.versionNumber());
	}

	public boolean requireOpForProfileCommand = true;
	public boolean requireOpForDumpCommand = true;
	private int profilingInterval = 0;
	private String profilingFileName = "world/computer/<computer id>/profile.txt";
	private boolean profilingJson = false;

	public static boolean shouldProfile(World w) {
		return profilingWorlds.contains(w);
	}

	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
		lastTickTime = System.nanoTime();
		MinecraftForge.EVENT_BUS.register(this);
		FMLCommonHandler.instance().bus().register(new ProfilingScheduledTickHandler(profilingInterval, new File(".", profilingFileName), profilingJson));
	}

	@SuppressWarnings("FieldRepeatedlyAccessedInMethod")
	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {
		Configuration config = new Configuration(event.getSuggestedConfigurationFile());
		config.load();
		String GENERAL = Configuration.CATEGORY_GENERAL;

		ProfileCommand.name = config.get(GENERAL, "profileCommandName", ProfileCommand.name, "Name of the command to be used for profiling reports.").getString();
		DumpCommand.name = config.get(GENERAL, "dumpCommandName", DumpCommand.name, "Name of the command to be used for dumping block data.").getString();
		TPSCommand.name = config.get(GENERAL, "tpsCommandName", TPSCommand.name, "Name of the command to be used for TPS reports.").getString();
		requireOpForProfileCommand = config.get(GENERAL, "requireOpForProfileCommand", requireOpForProfileCommand, "If a player must be opped to use /profile").getBoolean(requireOpForProfileCommand);
		requireOpForDumpCommand = config.get(GENERAL, "requireOpForProfileCommand", requireOpForDumpCommand, "If a player must be opped to use /dump").getBoolean(requireOpForDumpCommand);
		profilingInterval = config.get(GENERAL, "profilingInterval", profilingInterval, "Interval, in minutes, to record profiling information to disk. 0 = never. Recommended >= 2.").getInt();
		profilingFileName = config.get(GENERAL, "profilingFileName", profilingFileName, "Location to store profiling information to, relative to the server folder. For example, why not store it in a computercraft computer's folder?").getString();
		profilingJson = config.get(GENERAL, "profilingJson", profilingJson, "Whether to write periodic profiling in JSON format").getBoolean(profilingJson);
		config.save();
	}

	@Mod.EventHandler
	public void serverStarting(FMLServerStartingEvent event) {
		ServerCommandManager serverCommandManager = (ServerCommandManager) event.getServer().getCommandManager();
		serverCommandManager.registerCommand(new ProfileCommand());
		serverCommandManager.registerCommand(new DumpCommand());
		serverCommandManager.registerCommand(new TPSCommand());
	}

	public synchronized void hookProfiler(World world) {
		if (world.isRemote) {
			Log.error("World " + Log.name(world) + " seems to be a client world", new Throwable());
		}
		profilingWorlds.add(world);
	}

	public synchronized void unhookProfiler(World world) {
		if (world.isRemote) {
			Log.error("World " + Log.name(world) + " seems to be a client world", new Throwable());
		}
		if (!profilingWorlds.remove(world)) {
			throw new IllegalStateException("Not already profiling");
		}
	}

	@SubscribeEvent
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (event.action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
			EntityPlayer entityPlayer = event.entityPlayer;
			ItemStack usedItem = entityPlayer.getCurrentEquippedItem();
			if (usedItem != null) {
				Item usedItemType = usedItem.getItem();
				if (usedItemType == Items.clock && (!requireOpForDumpCommand || entityPlayer.canCommandSenderUseCommand(4, "dump"))) {
					Command.sendChat(entityPlayer, DumpCommand.dump(new TableFormatter(entityPlayer), entityPlayer.worldObj, event.x, event.y, event.z, 35).toString());
					event.setCanceled(true);
				}
			}
		}
	}

	public static class ProfilingScheduledTickHandler {
		private final int profilingInterval;
		private final File profilingFile;
		private final boolean json;
		private int counter = 0;

		public ProfilingScheduledTickHandler(final int profilingInterval, final File profilingFile, final boolean json) {
			this.profilingInterval = profilingInterval;
			this.profilingFile = profilingFile;
			this.json = json;
		}

		@SubscribeEvent
		public void tick(TickEvent.ServerTickEvent tick) {
			if (tick.phase != TickEvent.Phase.START) {
				return;
			}
			long time = System.nanoTime();
			long thisTickTime = time - lastTickTime;
			lastTickTime = time;
			LagSpikeProfiler.tick(time);
			tickTime = (tickTime * 19 + thisTickTime) / 20;
			final EntityTickProfiler entityTickProfiler = EntityTickProfiler.INSTANCE;
			entityTickProfiler.tick();
			int profilingInterval = this.profilingInterval;
			if (profilingInterval <= 0 || counter++ % (profilingInterval * 60 * 20) != 0) {
				return;
			}
			entityTickProfiler.startProfiling(new Runnable() {
				@Override
				public void run() {
					try {
						TableFormatter tf = new TableFormatter(MinecraftServer.getServer());
						tf.tableSeparator = "\n";
						if (json) {
							entityTickProfiler.writeJSONData(profilingFile);
						} else {
							Files.write(entityTickProfiler.writeStringData(tf, 6).toString(), profilingFile, Charsets.UTF_8);
						}
					} catch (Throwable t) {
						Log.error("Failed to save periodic profiling data to " + profilingFile, t);
					}
				}
			}, ProfileCommand.ProfilingState.ENTITIES, 10, Arrays.<World>asList(DimensionManager.getWorlds()));
		}
	}
}
