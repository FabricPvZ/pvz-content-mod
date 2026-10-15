package cn.enaium.fabricpvz.contentmod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cn.enaium.fabricpvz.portable.lawn.HelmType;
import cn.enaium.fabricpvz.portable.lawn.PlantSubClass;
import cn.enaium.fabricpvz.portable.lawn.ReanimationType;

/**
 * End-to-end verification of the content mod on the real Fabric Loader.
 *
 * <p>The test does exactly what a launcher does: it copies the <em>built mod jar</em> into a real
 * {@code mods/} directory and starts {@code net.fabricmc.loader.impl.launch.knot.KnotClient} in a
 * second JVM with the game, the loader (plus Mixin and ASM, pulled in by
 * {@code cn.enaium.fabricpvz:fabric-provider}) and the API modules on the class path. The loader
 * finds the game through the provider's {@code GameProvider}, resolves the mod, runs its
 * {@code ModInitializer}, registers its Mixin configuration and only then calls the game entry point
 * - which this test redirects to {@link cn.enaium.fabricpvz.contentmod.test.ContentModGameMain} with
 * {@code -Dpvz.game.mainClass}.
 *
 * <p>The harness reports through a file because the loader defines its own copies of the game, the
 * API and the mod classes; the assertions below are all made on that report.
 */
class ContentModEndToEndTest {

    /** Launcher main class of the real Fabric Loader (client environment), from its own manifest. */
    private static final String LAUNCHER_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";

    /** System property carrying the class path the launcher has to run with. */
    private static final String FORK_CLASSPATH_PROPERTY = "pvz.test.forkClasspath";

    /** System property naming the built mod jar. */
    private static final String MOD_JAR_PROPERTY = "pvz.test.modJar";

    /** Mod ids that must be resolved next to this mod, i.e. the game and the API modules. */
    private static final List<String> DEPENDENCY_MODS = List.of(
            "fabricloader", "pvz-portable",
            "fabric-api", "api-base", "api-events", "api-plant", "api-zombie");

    @TempDir
    Path tmp;

    @Test
    void bootsTheLoaderWithTheModJarAndRunsTheContentInARealBoard() throws Exception {
        Path modJar = Path.of(requiredProperty(MOD_JAR_PROPERTY));
        assertTrue(Files.isRegularFile(modJar), "the mod jar must be built first: " + modJar);
        assertModJarContents(modJar);

        Path mods = Files.createDirectories(tmp.resolve("mods"));
        Path installed = Files.copy(modJar, mods.resolve(modJar.getFileName()));
        assertTrue(Files.size(installed) > 0, "the installed mod jar must not be empty");

        Path runDir = Files.createDirectories(tmp.resolve("run"));
        Path report = tmp.resolve("content-report.txt");
        Path output = tmp.resolve("loader-output.txt");

        int exitCode = launch(mods, runDir, report, output);
        assertEquals(0, exitCode, "Fabric Loader must boot the game with the mod: " + failureMessage(output, report));

        Map<String, String> values = readReport(report);

        // 1) The loader resolved the game, the API modules and this mod jar.
        assertEquals("1.0.0", values.get("gameVersion"), "the provider must report the game's version");
        assertEquals("client", values.get("environment"));
        assertEquals("true", values.get("contentModLoaded"));
        for (String id : DEPENDENCY_MODS) {
            assertTrue(List.of(values.get("modsLoaded").split(",")).contains(id),
                    "mod " + id + " must be loaded, got " + values.get("modsLoaded"));
        }
        assertEquals("true", values.get("contentModEntrypointRan"), "the mod's ModInitializer must have run");
        assertEquals("true", values.get("apiInitialized"), "the API entry point must run before the game");
        assertEquals("1.0.0", values.get("apiVersion"));

        // 2) Both plants and the zombie are registered through the public API and the game's own
        //    definition tables report the registered stats.
        assertEquals("2", values.get("plantsRegistered"));
        assertEquals("1", values.get("zombiesRegistered"));
        assertEquals("true", values.get("sniperPeaRegistered"));
        assertEquals("true", values.get("sunOrchidRegistered"));
        assertEquals("true", values.get("bulwarkZombieRegistered"));
        assertEquals("true", values.get("seedTypesOutsideUpstream"));
        assertEquals("true", values.get("zombieTypeOutsideUpstream"));

        assertEquals("200", values.get("sniperPeaDefinitionCost"));
        assertEquals("750", values.get("sniperPeaDefinitionRefresh"));
        assertEquals("100", values.get("sniperPeaDefinitionLaunchRate"), "the Sniper Pea fires faster");
        assertEquals(String.valueOf(ReanimationType.REANIM_PEASHOOTER), values.get("sniperPeaDefinitionReanim"));
        assertEquals(String.valueOf(PlantSubClass.SUBCLASS_SHOOTER), values.get("sniperPeaDefinitionSubclass"));
        assertEquals("Sniper Pea", values.get("sniperPeaName"));
        assertEquals("Fires hardened peas down the whole lane.", values.get("sniperPeaTooltip"));
        assertTrue(values.get("sniperPeaAlmanac").startsWith("Sniper Pea is a marksman."),
                "almanac text, got " + values.get("sniperPeaAlmanac"));

        assertEquals("25", values.get("sunOrchidDefinitionCost"));
        assertEquals("2500", values.get("sunOrchidDefinitionLaunchRate"));
        assertEquals(String.valueOf(ReanimationType.REANIM_SUNFLOWER), values.get("sunOrchidDefinitionReanim"));
        assertEquals("Sun Orchid", values.get("sunOrchidName"));
        assertTrue(values.get("sunOrchidAlmanac").startsWith("Sun Orchid is a bargain:"),
                "almanac text, got " + values.get("sunOrchidAlmanac"));

        assertEquals("Bulwark Zombie", values.get("bulwarkName"));
        assertTrue(values.get("bulwarkAlmanac").startsWith("Bulwark Zombie wears a pail"),
                "almanac text, got " + values.get("bulwarkAlmanac"));
        assertEquals("3", values.get("bulwarkValue"), "the registered wave cost");
        assertEquals("1", values.get("bulwarkStartingLevel"));
        assertEquals("1", values.get("bulwarkFirstAllowedWave"));
        assertEquals("2000", values.get("bulwarkPickWeight"));

        // Upstream content is untouched.
        assertEquals("100", values.get("peashooterDefinitionCost"));
        assertEquals("150", values.get("peashooterDefinitionLaunchRate"));
        assertEquals("1", values.get("normalZombieAllowed"));

        // 3) The mod's art: both packet images came out of the mod jar, are in the game's resource
        //    table, and the mod's Mixin put them back after a resource group load clobbered them.
        assertEquals("none", values.get("assetLoadError"));
        assertEquals("true", values.get("packetArtBound"), "the packet art must be bound on startup");
        assertEquals("72x70", values.get("sniperPeaPacketSize"));
        assertEquals("72x70", values.get("sunOrchidPacketSize"));
        assertEquals("53x63", values.get("zombieTorsoSize"));
        assertTrue(values.get("sniperPeaPacketCentrePixel").startsWith("0x"),
                "the packet image must hold real pixels, got " + values.get("sniperPeaPacketCentrePixel"));
        assertEquals("true", values.get("sniperPeaPacketSlotIsImage"));
        assertEquals("true", values.get("sunOrchidPacketSlotIsImage"));
        assertEquals("218", values.get("sniperPeaPacketSlot"));
        assertEquals("226", values.get("sunOrchidPacketSlot"));
        assertEquals("false", values.get("packetArtBoundAfterClobber"), "the simulated group load wipes it");
        assertTrue(Integer.parseInt(values.get("mixinCallbacks")) >= 1,
                "the mod's ResourcesMixin must have been applied to the game class, got "
                        + values.get("mixinCallbacks"));
        assertEquals("true", values.get("packetArtBoundAfterGroupLoad"),
                "the mixin must re-install the packet art after a resource group load");
        assertEquals("true", values.get("packetArtBoundAfterTickHook"),
                "the LawnEvents.TICK safety net must restore the art too");

        if (Boolean.parseBoolean(values.get("assetsPresent"))) {
            assertEquals("true", values.get("retailPeaSplatsInPak"),
                    "the Sniper Pea's packet slot must be a real retail resource");
            assertEquals("true", values.get("retailPotatomineParticlesInPak"),
                    "the Sun Orchid's packet slot must be a real retail resource");
        } else {
            System.out.println("[content-mod-e2e] no retail assets on this machine: the retail archive "
                    + "check was skipped, the registration and board checks still ran");
        }

        // 4) A real board: both plants are placeable, have the registered stats and run their hooks.
        assertEquals("1", values.get("boardLevel"));
        assertEquals("true", values.get("sniperPeaSeedAvailable"), "the seed packet must be usable");
        assertEquals("true", values.get("sunOrchidSeedAvailable"));
        assertEquals("true", values.get("plantingRuleAllowsColumn7"));
        assertEquals("true", values.get("plantingRuleVetoesEntryColumn"),
                "the Sniper Pea must refuse the column the zombies walk in");
        assertEquals("true", values.get("plantingRuleLeavesUpstreamAlone"));

        assertEquals("true", values.get("sniperPeaPlaced"));
        assertEquals("true", values.get("sniperPeaSeedTypeMatches"));
        assertEquals("500", values.get("sniperPeaHealth"));
        assertEquals("500", values.get("sniperPeaMaxHealth"));
        assertEquals("100", values.get("sniperPeaLaunchRate"));
        assertEquals("true", values.get("sniperPeaBodyReanimLoaded"));
        assertEquals("1200", values.get("sniperPeaAttackRect").split(",")[2],
                "the whole-lane attack rectangle must come from the mod, got "
                        + values.get("sniperPeaAttackRect"));
        assertEquals("800", values.get("upstreamPeashooterAttackRect").split(",")[2],
                "the upstream peashooter keeps its own attack rectangle");

        assertEquals("true", values.get("sunOrchidPlaced"));
        assertEquals("350", values.get("sunOrchidHealth"));
        assertEquals("350", values.get("sunOrchidMaxHealth"));
        assertEquals("2500", values.get("sunOrchidLaunchRate"));
        assertEquals(Integer.parseInt(values.get("sunOrchidOwnWidth")) + 24,
                Integer.parseInt(values.get("sunOrchidRectWidth")),
                "the orchid's collision rectangle must be 12px wider on each side");
        assertEquals("1", values.get("sunOrchidSunsProduced"), "the orchid must drop a sun on its cadence");
        assertEquals("1", values.get("sunOrchidCoinsAdded"), "the sun must be a real collectable coin");
        assertEquals("1", values.get("orchidTickCallbacks"),
                "the Sun Orchid is the plant with a tick hook, and its hook must run on every update");
        assertEquals("3", values.get("boardPlantCount"));

        // 5) The zombie spawns through the board's own spawner with the registered numbers, walks and
        //    dies.
        assertEquals("true", values.get("zombieSpawned"));
        assertEquals("true", values.get("zombieTypeMatches"));
        assertEquals("400", values.get("zombieBodyHealth"));
        assertEquals("400", values.get("zombieBodyMaxHealth"));
        assertEquals(String.valueOf(HelmType.HELMTYPE_PAIL), values.get("zombieHelmType"));
        assertEquals("1500", values.get("zombieHelmHealth"));
        assertEquals("0.92", values.get("zombieScale"));
        assertEquals("70,0,20,115", values.get("zombieAttackRect"));
        assertEquals("50,0,20,115", values.get("plainZombieAttackRect"),
                "the plain zombie's own eating rectangle is unchanged");
        assertEquals("true", values.get("zombieBodyReanimLoaded"));
        assertEquals("1", values.get("zombieTorsosRecoloured"), "the recoloured torso must be applied");
        assertEquals("true", values.get("zombieAllowedOnLevel"));
        assertTrue(Integer.parseInt(values.get("wavesContainingModZombie")) >= 1,
                "the mod zombie must take part in the level's waves");
        assertEquals("true", values.get("upstreamZombieStillAllowed"));

        assertTrue(Float.parseFloat(values.get("zombieWalkedDistance")) > 0.0f,
                "the zombie must walk towards the house, walked " + values.get("zombieWalkedDistance"));
        assertEquals("30", values.get("zombieTickCallbacks"), "the zombie's tick hook must run every tick");
        assertEquals("1", values.get("zombieHelmRepairs"), "the pail must repair itself on its cadence");
        assertEquals("101", values.get("zombieHelmHealthAfterTick"));
        assertEquals("0", values.get("zombieBodyHealthAfterLethalHit"),
                "a lethal hit must take the registered zombie's body health to zero");
        assertEquals("true", values.get("zombieDying"),
                "the registered zombie must enter the game's dead-or-dying state");
        assertEquals("true", values.get("zombieDead"),
                "the game must mark the registered zombie dead within " + values.get("zombieDeathTicks")
                        + " ticks");

        // 6) The game is still running the level, and the harness saw no exception at all.
        assertEquals("PLAYING", values.get("scene"));
        assertEquals("none", values.get("harnessError"));
    }

    /** The jar a player drops into {@code mods/} must carry the metadata, the code, the mixin and the art. */
    private void assertModJarContents(Path modJar) throws IOException {
        Set<String> entries;
        try (JarFile jar = new JarFile(modJar.toFile())) {
            entries = jar.stream().map(JarEntry::getName).collect(Collectors.toSet());
        }

        assertTrue(entries.contains("fabric.mod.json"), "the jar must carry fabric.mod.json, got " + entries);
        assertTrue(entries.contains("pvz-content-mod.mixins.json"), "the jar must carry the mixin config");
        assertTrue(entries.contains("cn/enaium/fabricpvz/contentmod/ContentMod.class"), "the entry point class");
        assertTrue(entries.contains("cn/enaium/fabricpvz/contentmod/ContentModAssets.class"));
        assertTrue(entries.contains("cn/enaium/fabricpvz/contentmod/mixin/ResourcesMixin.class"));
        assertTrue(entries.contains("assets/pvz-content-mod/textures/packet_sniper_pea.png"),
                "the generated packet art must ship in the jar");
        assertTrue(entries.contains("assets/pvz-content-mod/textures/packet_sun_orchid.png"));
        assertTrue(entries.contains("assets/pvz-content-mod/textures/zombie_torso.png"));
    }

    /**
     * Starts the real Fabric Loader in a second JVM, exactly like the launcher would.
     *
     * @return the launcher's exit code
     */
    private int launch(Path mods, Path runDir, Path report, Path output) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("--enable-native-access=ALL-UNNAMED");

        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac")) {
            // SDL (LWJGL's bindings) needs the first thread.
            command.add("-XstartOnFirstThread");
        }

        command.add("-cp");
        command.add(forkClasspath());
        // The harness entry point lives in this project's test classes, which are not part of the
        // launcher's class path proper: the provider adds these entries to the loader's own class
        // loader, so the harness and the mod/API/game classes it inspects are all defined by the same
        // class loader (otherwise it would read a second copy of every static).
        command.add("-Dpvz.extraClasspath=" + harnessDirectory());
        // Development mode: the loader also discovers the mods that are on the class path (the API
        // modules resolved from mavenLocal). The mod under test is deliberately NOT there: it is only
        // in the mods directory, exactly like a jar a player copied into the installation.
        command.add("-Dfabric.development=true");
        // This port is the only provider on the class path.
        command.add("-Dfabric.skipMcProvider=true");
        // A crash must exit instead of opening the loader's Swing error window.
        command.add("-Dfabric.noGui=true");
        command.add("-Dpvz.gameDir=" + runDir);
        command.add("-Dfabric.modsFolder=" + mods);
        command.add("-Dpvz.game.mainClass=cn.enaium.fabricpvz.contentmod.test.ContentModGameMain");
        command.add("-Dpvz.test.report=" + report);
        String assets = resourceRoot();
        if (!assets.isBlank()) {
            command.add("-Dpvz.test.resources=" + assets);
        }
        command.add(LAUNCHER_CLASS);
        command.add("-savedir");
        command.add(runDir.resolve("userdata").toString());

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(tmp.toFile())
                .redirectOutput(output.toFile())
                .redirectErrorStream(true);
        System.out.println("[content-mod-e2e] boot command:\n  " + String.join(" \\\n  ", command));
        Process process = builder.start();

        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("Fabric Loader did not finish; launcher output:\n" + read(output));
        }

        System.out.println("[content-mod-e2e] " + LAUNCHER_CLASS + " exited with " + process.exitValue());
        System.out.println("[content-mod-e2e] launcher output:\n" + read(output));
        return process.exitValue();
    }

    /**
     * The class path the launcher runs with: the test classes, the loader, the API modules, the game
     * and the game provider - but not this project's own main output, so the mod can only come from
     * the mods directory.
     */
    private static String forkClasspath() {
        String classpath = System.getProperty(FORK_CLASSPATH_PROPERTY, "");
        if (classpath.isBlank()) {
            fail("-D" + FORK_CLASSPATH_PROPERTY + " is not set (run through Gradle)");
        }
        return classpath;
    }

    /**
     * The directory holding the harness classes (this test's own output directory).
     */
    private static String harnessDirectory() {
        try {
            return Path.of(ContentModEndToEndTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot locate the harness classes", e);
        }
    }

    /**
     * The retail resource root this machine has installed, or an empty string. Discovered with the
     * game's own locator (the Steam library search), so the run uses the real assets when they are
     * there and still verifies the registrations when they are not.
     */
    private static String resourceRoot() {
        String override = System.getProperty("pvz.test.resources", "");
        if (!override.isBlank()) {
            return override;
        }

        Path discovered = cn.enaium.fabricpvz.portable.pvzp.ResourceLocator.discover(new String[0]).resourceRoot();
        return discovered == null ? "" : discovered.toString();
    }

    private static Map<String, String> readReport(Path report) throws IOException {
        assertTrue(Files.isRegularFile(report), "the harness must have written its report");
        return parse(Files.readString(report));
    }

    private static Map<String, String> parse(String content) {
        Map<String, String> values = new HashMap<>();
        for (String line : content.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                values.put(line.substring(0, equals), line.substring(equals + 1).trim());
            }
        }
        return values;
    }

    private static String read(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path) : "<no output>";
    }

    private static String failureMessage(Path output, Path report) throws IOException {
        String tail = read(output);
        int keep = 4000;
        if (tail.length() > keep) {
            tail = "..." + tail.substring(tail.length() - keep);
        }
        return "\n--- launcher output (tail) ---\n" + tail
                + "\n--- report ---\n" + (Files.exists(report) ? Files.readString(report) : "<none>");
    }

    private static String requiredProperty(String theName) {
        String value = System.getProperty(theName, "");
        assertNotNull(value, "-D" + theName + " is required");
        assertTrue(!value.isBlank(), "-D" + theName + " is required");
        return value;
    }
}
