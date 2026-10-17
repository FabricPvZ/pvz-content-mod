package cn.enaium.fabricpvz.contentmod;

import cn.enaium.fabricpvz.api.plant.PlantType;
import cn.enaium.fabricpvz.portable.lawn.PlantSubClass;
import cn.enaium.fabricpvz.portable.lawn.ProjectileType;
import cn.enaium.fabricpvz.portable.lawn.ReanimationType;
import cn.enaium.fabricpvz.portable.lawn.SeedType;
import net.fabricmc.api.ModInitializer;

/**
 * The content mod: three custom plants, the Red Peashooter, the Red Repeater and the Red Gatling Pea,
 * registered through the public content API only.
 *
 * <table>
 *   <caption>Content</caption>
 *   <tr><th>name</th><th>type</th><th>stats</th><th>behaviour</th></tr>
 *   <tr>
 *     <td>Red Peashooter</td><td>plant</td>
 *     <td>175 sun, 300 hp, fires every 150 ticks like the peashooter, one fire pea, unlocked with the
 *     Snow Pea</td>
 *     <td rowspan="3">their retail peashooter-family art is recoloured red on planting; the seed
 *     packets show the red plants as well</td>
 *   </tr>
 *   <tr>
 *     <td>Red Repeater</td><td>plant</td>
 *     <td>225 sun, the repeater's 750 recharge and 150 launch rate, two fire peas per launch, unlocked
 *     once the Repeater is</td>
 *   </tr>
 *   <tr>
 *     <td>Red Gatling Pea</td><td>plant</td>
 *     <td>275 sun, the gatling pea's 5000 recharge and 150 launch rate, four fire peas per launch,
 *     unlocked once the shop's Gatling Pea is bought; a purple (upgrade) seed packet like the shop
 *     plant's own, and plantable on a repeater - retail or red - as that plant's upgrade</td>
 *   </tr>
 * </table>
 *
 * <p>Everything is plain Java and everything the mod does not itself own comes from the API modules:
 * registration and the plants' stats from {@code api-plant}, the events from {@code api-events}, and
 * the art - the game's own painters, the per-track recolouring - from {@code api-resource} (see
 * {@link RedPlantsArt}). Nothing is reflected and no game class is patched.
 */
public final class ContentMod implements ModInitializer {

    /** Mod id, matching {@code fabric.mod.json}. */
    public static final String MOD_ID = "pvz-content-mod";

    // ---------------------------------------------------------------- Red Peashooter

    /** Registration id of the Red Peashooter. */
    public static final String PEASHOOTER_ID = "red_peashooter";
    /** Sun cost of one Red Peashooter seed packet; a snow pea costs 175 as well. */
    public static final int RED_PEASHOOTER_COST = 175;
    /** Recharge time of the seed packet in centiseconds, the peashooter's own. */
    public static final int RED_PEASHOOTER_REFRESH = 750;
    /** Ticks between shots; the peashooter's own launch rate. */
    public static final int RED_PEASHOOTER_LAUNCH_RATE = 150;
    /**
     * What the almanac says about the Red Peashooter, i.e. the text of {@code almanacDescription}; the
     * game's almanac plant page shows it on the page behind the retail grid.
     */
    public static final String RED_PEASHOOTER_ALMANAC_DESCRIPTION =
            "The peashooter's red twin. Its peas burn on contact instead of bouncing off.";

    /** The registered Red Peashooter. */
    public static PlantType redPeashooter;

    // ---------------------------------------------------------------- Red Repeater

    /** Registration id of the Red Repeater. */
    public static final String REPEATER_ID = "red_repeater";
    /** Sun cost of one Red Repeater seed packet. */
    public static final int RED_REPEATER_COST = 225;
    /** Recharge time of the seed packet in centiseconds, the repeater's own. */
    public static final int RED_REPEATER_REFRESH = 750;
    /** Ticks between volleys; the repeater's own launch rate. */
    public static final int RED_REPEATER_LAUNCH_RATE = 150;
    /** The almanac text of the Red Repeater. */
    public static final String RED_REPEATER_ALMANAC_DESCRIPTION =
            "The repeater's red twin. Two burning peas per volley, on the repeater's own cadence.";

    /** The registered Red Repeater. */
    public static PlantType redRepeater;

    // ---------------------------------------------------------------- Red Gatling Pea

    /** Registration id of the Red Gatling Pea. */
    public static final String GATLING_ID = "red_gatling";
    /** Sun cost of one Red Gatling Pea seed packet. */
    public static final int RED_GATLING_COST = 275;
    /** Recharge time of the seed packet in centiseconds, the gatling pea's own (the shop's long one). */
    public static final int RED_GATLING_REFRESH = 5000;
    /** Ticks between volleys; the gatling pea's own launch rate. */
    public static final int RED_GATLING_LAUNCH_RATE = 150;
    /** The almanac text of the Red Gatling Pea. */
    public static final String RED_GATLING_ALMANAC_DESCRIPTION =
            "The gatling pea's red twin. Four burning peas per volley, on the shop plant's own cadence.";

    /** The registered Red Gatling Pea. */
    public static PlantType redGatling;

    @Override
    public void onInitialize() {
        RedPlantsArt.install();
        registerRedPeashooter();
        registerRedRepeater();
        registerRedGatling();
    }

    /**
     * The peashooter's red twin: the peashooter's own cost, cadence and health with fire peas instead
     * of plain ones. Its art is the retail peashooter's, recoloured red on planting
     * ({@link RedPlantsArt#onPlanted}), and it is released by the Snow Pea's award like a plant of the
     * adventure's own progression. Its tick hook re-applies that art to the blink reanimation the game
     * allocates per blink.
     */
    private static void registerRedPeashooter() {
        redPeashooter = PlantType.builder(PEASHOOTER_ID)
                .name("Red Peashooter")
                .tooltip("Fires burning peas instead of plain ones.")
                .almanacDescription(RED_PEASHOOTER_ALMANAC_DESCRIPTION)
                .cost(RED_PEASHOOTER_COST)
                .refreshTime(RED_PEASHOOTER_REFRESH)
                .reanimation(ReanimationType.REANIM_PEASHOOTER)
                .subClass(PlantSubClass.SUBCLASS_SHOOTER)
                .launchRate(RED_PEASHOOTER_LAUNCH_RATE)
                .projectile(ProjectileType.PROJECTILE_FIREBALL)
                .unlockAfter(SeedType.SEED_SNOWPEA)
                .packetArt(() -> RedPlantsArt.packetImage(PEASHOOTER_ID))
                .onPlanted(RedPlantsArt::onPlanted)
                .onTick(RedPlantsArt::onTick)
                .register();
    }

    /**
     * The repeater's red twin: the same cost, recharge and launch rate as the retail repeater with fire
     * peas, and the repeater's two peas per volley ({@code .burst(2)}; without it a registered plant
     * fires one, see {@code PlantType.Builder#burst}). Unlocked once the adventure has awarded the
     * Repeater, exactly like the plant it copies; not a shop item.
     */
    private static void registerRedRepeater() {
        redRepeater = PlantType.builder(REPEATER_ID)
                .name("Red Repeater")
                .tooltip("Fires two burning peas per volley.")
                .almanacDescription(RED_REPEATER_ALMANAC_DESCRIPTION)
                .cost(RED_REPEATER_COST)
                .refreshTime(RED_REPEATER_REFRESH)
                .reanimation(ReanimationType.REANIM_REPEATER)
                .subClass(PlantSubClass.SUBCLASS_SHOOTER)
                .launchRate(RED_REPEATER_LAUNCH_RATE)
                .burst(2)
                .projectile(ProjectileType.PROJECTILE_FIREBALL)
                .unlockAfter(SeedType.SEED_REPEATER)
                .packetArt(() -> RedPlantsArt.packetImage(REPEATER_ID))
                .onPlanted(RedPlantsArt::onPlanted)
                .onTick(RedPlantsArt::onTick)
                .register();
    }

    /**
     * The gatling pea's red twin: the same recharge and launch rate as the retail gatling pea with fire
     * peas, and its four peas per volley ({@code .burst(4)}). The retail gatling pea is a shop plant, so
     * gating this one behind {@code SeedType.SEED_GATLINGPEA} means the game's own {@code HasSeedType}
     * releases it the moment the player buys the shop item - the mod itself never enters the shop.
     *
     * <p>{@code .upgrade(true)} is the one call the purple packet takes: {@code Plant::IsUpgrade} then
     * answers {@code true} for this seed type ({@code Plant.cpp:5124}, through the API's Mixin) and the
     * game draws the packet on the upgrade background, exactly like its own gatling pea.
     *
     * <p>{@code .upgradeFrom(SEED_REPEATER, redRepeater.seedType())} is the other half, and the one
     * the user's report was about: {@code Plant::IsUpgradableTo} ({@code Plant.cpp:2608}, through the
     * API's Mixin) then answers {@code true} for this seed on a square holding either repeater, so the
     * square reports {@code PLANTING_OK} and the planting branch of {@code BoardInput} replaces that
     * plant with this one. The retail repeater is listed because the red gatling is that plant's red
     * twin, exactly like the shop's gatling pea is plantable on it.
     */
    private static void registerRedGatling() {
        redGatling = PlantType.builder(GATLING_ID)
                .name("Red Gatling Pea")
                .tooltip("Fires a four-pea burst of burning peas.")
                .almanacDescription(RED_GATLING_ALMANAC_DESCRIPTION)
                .cost(RED_GATLING_COST)
                .refreshTime(RED_GATLING_REFRESH)
                .reanimation(ReanimationType.REANIM_GATLINGPEA)
                .subClass(PlantSubClass.SUBCLASS_SHOOTER)
                .launchRate(RED_GATLING_LAUNCH_RATE)
                .burst(4)
                .upgrade(true)
                .upgradeFrom(SeedType.SEED_REPEATER, ContentMod.redRepeater.seedType())
                .projectile(ProjectileType.PROJECTILE_FIREBALL)
                .unlockAfter(SeedType.SEED_GATLINGPEA)
                .packetArt(() -> RedPlantsArt.packetImage(GATLING_ID))
                .onPlanted(RedPlantsArt::onPlanted)
                .onTick(RedPlantsArt::onTick)
                .register();
    }

    /**
     * @param theRetailSeedType a retail seed type one of this mod's plants copies
     * @return the registered plant whose art copies it, or {@code null} when none does
     */
    static PlantType plantCopying(int theRetailSeedType) {
        return switch (theRetailSeedType) {
            case SeedType.SEED_PEASHOOTER -> redPeashooter;
            case SeedType.SEED_REPEATER -> redRepeater;
            case SeedType.SEED_GATLINGPEA -> redGatling;
            default -> null;
        };
    }
}
