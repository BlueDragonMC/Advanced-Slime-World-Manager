package com.grinderwolf.swm.plugin;

import com.flowpowered.nbt.CompoundMap;
import com.flowpowered.nbt.CompoundTag;
import com.grinderwolf.swm.api.SlimePlugin;
import com.grinderwolf.swm.api.exceptions.*;
import com.grinderwolf.swm.api.loaders.SlimeLoader;
import com.grinderwolf.swm.api.world.SlimeWorld;
import com.grinderwolf.swm.api.world.properties.SlimePropertyMap;
import com.grinderwolf.swm.nms.CraftSlimeWorld;
import com.grinderwolf.swm.nms.SlimeNMS;
import com.grinderwolf.swm.nms.v1_16_R1.v1_16_R1SlimeNMS;
import com.grinderwolf.swm.nms.v1_16_R2.v1_16_R2SlimeNMS;
import com.grinderwolf.swm.nms.v1_16_R3.v1_16_R3SlimeNMS;
import com.grinderwolf.swm.plugin.commands.CommandManager;
import com.grinderwolf.swm.plugin.config.ConfigManager;
import com.grinderwolf.swm.plugin.config.WorldData;
import com.grinderwolf.swm.plugin.loaders.LoaderUtils;
import com.grinderwolf.swm.plugin.log.Logging;
import com.grinderwolf.swm.plugin.update.Updater;
import com.grinderwolf.swm.plugin.upgrade.WorldUpgrader;
import com.grinderwolf.swm.plugin.world.WorldUnlocker;
import com.grinderwolf.swm.plugin.world.importer.WorldImporter;
import lombok.Getter;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.grinderwolf.swm.api.world.properties.SlimeProperties.*;

public class SWMPlugin extends JavaPlugin implements SlimePlugin {

    @Getter
    private SlimeNMS nms;

    private static boolean isPaperMC = false;

    private static boolean checkIsPaper() {
        try {
            //todo this breaks when Paper changes its package name to io.papermc.paper
            return Class.forName("com.destroystokyo.paper.PaperConfig") != null;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    @Override
    public void onLoad() {
        isPaperMC = checkIsPaper();

        try {
            ConfigManager.initialize();
        } catch (NullPointerException | IOException | ObjectMappingException ex) {
            Logging.error("Failed to load config files:");
            ex.printStackTrace();
            return;
        }

        LoaderUtils.registerLoaders();

        try {
            nms = getNMSBridge();
        } catch (InvalidVersionException ex) {
            Logging.error(ex.getMessage());
            return;
        }

        try {
            nms.setDefaultWorlds(null, null, null);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onEnable() {
        if (nms == null) {
            this.setEnabled(false);
            return;
        }

        // new Metrics(this);

        final CommandManager commandManager = new CommandManager();
        final PluginCommand swmCommand = getCommand("swm");
        swmCommand.setExecutor(commandManager);

        try {
            swmCommand.setTabCompleter(commandManager);
        } catch (Throwable throwable) {
            // For some versions that does not have TabComplete?
        }

        getServer().getPluginManager().registerEvents(new WorldUnlocker(), this);

        if (ConfigManager.getMainConfig().getUpdaterOptions().isEnabled()) {
            getServer().getPluginManager().registerEvents(new Updater(), this);
        }
    }

    private SlimeNMS getNMSBridge() throws InvalidVersionException {
        String version = Bukkit.getServer().getClass().getPackage().getName();
        String nmsVersion = version.substring(version.lastIndexOf('.') + 1);

        switch (nmsVersion) {
            case "v1_16_R1":
                return new v1_16_R1SlimeNMS(isPaperMC);
            case "v1_16_R2":
                return new v1_16_R2SlimeNMS(isPaperMC);
            case "v1_16_R3":
                return new v1_16_R3SlimeNMS(isPaperMC);
            default:
                throw new InvalidVersionException(nmsVersion);
        }
    }

    @Override
    public SlimeWorld loadWorld(SlimeLoader loader, String fileName, String worldName, boolean readOnly, SlimePropertyMap propertyMap) throws UnknownWorldException, IOException,
            CorruptedWorldException, NewerFormatException, WorldInUseException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(fileName, "File name cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        long start = System.currentTimeMillis();

        Logging.info("Loading world " + worldName + " (" + fileName + ").");
        byte[] serializedWorld = loader.loadWorld(fileName, readOnly);
        CraftSlimeWorld world;

        try {
            world = LoaderUtils.deserializeWorld(loader, worldName, serializedWorld, propertyMap, readOnly);

            if (world.getVersion() > nms.getWorldVersion()) {
                WorldUpgrader.downgradeWorld(world);
            } else if (world.getVersion() < nms.getWorldVersion()) {
                WorldUpgrader.upgradeWorld(world);
            }
        } catch (Exception ex) {
            if (!readOnly) { // Unlock the world as we're not using it
                loader.unlockWorld(fileName);
            }

            throw ex;
        }

        ConfigManager.getWorlds().put(fileName, new WorldData(
                loader.getClass().getPackageName(),
                propertyMap.getValue(SPAWN_X) + "," + propertyMap.getValue(SPAWN_Y) + "," + propertyMap.getValue(SPAWN_Z),
                propertyMap.getValue(DIFFICULTY),
                propertyMap.getValue(ALLOW_ANIMALS),
                propertyMap.getValue(ALLOW_MONSTERS),
                propertyMap.getValue(PVP),
                propertyMap.getValue(ENVIRONMENT),
                propertyMap.getValue(WORLD_TYPE),
                propertyMap.getValue(DEFAULT_BIOME),
                false, //No worlds are auto loaded on startup anyways
                true   //No world data is ever saved
        ));
        Logging.info("World " + fileName + " loaded in " + (System.currentTimeMillis() - start) + "ms.");

        return world;
    }

    @Override
    public SlimeWorld createEmptyWorld(SlimeLoader loader, String worldName, boolean readOnly, SlimePropertyMap propertyMap) throws WorldAlreadyExistsException, IOException {
        Objects.requireNonNull(loader, "Loader cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(propertyMap, "Properties cannot be null");

        if (loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        Logging.info("Creating empty world " + worldName + ".");
        long start = System.currentTimeMillis();
        CraftSlimeWorld world = new CraftSlimeWorld(loader, worldName, new HashMap<>(), new CompoundTag("",
                new CompoundMap()), new ArrayList<>(), nms.getWorldVersion(), propertyMap, readOnly, !readOnly);
        loader.saveWorld(worldName, world.serialize(), !readOnly);

        Logging.info("World " + worldName + " created in " + (System.currentTimeMillis() - start) + "ms.");

        return world;
    }

    private SlimePropertyMap propertiesToMap(SlimeWorld.SlimeProperties properties) {
        SlimePropertyMap propertyMap = new SlimePropertyMap();

        propertyMap.setValue(SPAWN_X, (int) properties.getSpawnX());
        propertyMap.setValue(SPAWN_Y, (int) properties.getSpawnY());
        propertyMap.setValue(SPAWN_Z, (int) properties.getSpawnZ());
        propertyMap.setValue(DIFFICULTY, Difficulty.getByValue(properties.getDifficulty()).name());
        propertyMap.setValue(ALLOW_MONSTERS, properties.allowMonsters());
        propertyMap.setValue(ALLOW_ANIMALS, properties.allowAnimals());
        propertyMap.setValue(PVP, properties.isPvp());
        propertyMap.setValue(ENVIRONMENT, properties.getEnvironment());

        return propertyMap;
    }

    @Override
    public void generateWorld(SlimeWorld world) {
        Objects.requireNonNull(world, "SlimeWorld cannot be null");

        if (!world.isReadOnly() && !world.isLocked()) {
            throw new IllegalArgumentException("This world cannot be loaded, as it has not been locked.");
        }

        nms.generateWorld(world);
    }

    @Override
    public void migrateWorld(String worldName, SlimeLoader currentLoader, SlimeLoader newLoader) throws IOException,
            WorldInUseException, WorldAlreadyExistsException, UnknownWorldException {
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(currentLoader, "Current loader cannot be null");
        Objects.requireNonNull(newLoader, "New loader cannot be null");

        if (newLoader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        World bukkitWorld = Bukkit.getWorld(worldName);

        boolean leaveLock = false;

        if (bukkitWorld != null) {
            // Make sure the loaded world really is a SlimeWorld and not a normal Bukkit world
            CraftSlimeWorld slimeWorld = (CraftSlimeWorld) SWMPlugin.getInstance().getNms().getSlimeWorld(bukkitWorld);

            if (slimeWorld != null && currentLoader.equals(slimeWorld.getLoader())) {
                slimeWorld.setLoader(newLoader);

                if (!slimeWorld.isReadOnly()) { // We have to manually unlock the world so no WorldInUseException is thrown
                    currentLoader.unlockWorld(worldName);
                    leaveLock = true;
                }
            }
        }

        byte[] serializedWorld = currentLoader.loadWorld(worldName, false);

        newLoader.saveWorld(worldName, serializedWorld, leaveLock);
        currentLoader.deleteWorld(worldName);
    }

    @Override
    public SlimeLoader getLoader(String dataSource) {
        Objects.requireNonNull(dataSource, "Data source cannot be null");

        return LoaderUtils.getLoader(dataSource);
    }

    @Override
    public void registerLoader(String dataSource, SlimeLoader loader) {
        Objects.requireNonNull(dataSource, "Data source cannot be null");
        Objects.requireNonNull(loader, "Loader cannot be null");

        LoaderUtils.registerLoader(dataSource, loader);
    }

    @Override
    public void importWorld(File worldDir, String worldName, SlimeLoader loader) throws WorldAlreadyExistsException,
            InvalidWorldException, WorldLoadedException, WorldTooBigException, IOException {
        Objects.requireNonNull(worldDir, "World directory cannot be null");
        Objects.requireNonNull(worldName, "World name cannot be null");
        Objects.requireNonNull(loader, "Loader cannot be null");

        if (loader.worldExists(worldName)) {
            throw new WorldAlreadyExistsException(worldName);
        }

        World bukkitWorld = Bukkit.getWorld(worldDir.getName());

        if (bukkitWorld != null && nms.getSlimeWorld(bukkitWorld) == null) {
            throw new WorldLoadedException(worldDir.getName());
        }

        CraftSlimeWorld world = WorldImporter.readFromDirectory(worldDir);

        byte[] serializedWorld;

        try {
            serializedWorld = world.serialize();
        } catch (IndexOutOfBoundsException ex) {
            throw new WorldTooBigException(worldDir.getName());
        }

        loader.saveWorld(worldName, serializedWorld, false);
    }

    public static boolean isPaperMC() {
        return isPaperMC;
    }

    public static SWMPlugin getInstance() {
        return SWMPlugin.getPlugin(SWMPlugin.class);
    }
}