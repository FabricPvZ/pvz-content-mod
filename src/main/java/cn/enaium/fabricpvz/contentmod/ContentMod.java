package cn.enaium.fabricpvz.contentmod;

import cn.enaium.fabricpvz.api.lawn.LawnEvents;
import cn.enaium.fabricpvz.api.lifecycle.GameLifecycleEvents;
import cn.enaium.fabricpvz.api.plant.PlantType;
import cn.enaium.fabricpvz.api.zombie.ZombieType;
import cn.enaium.fabricpvz.portable.lawn.Board;
import cn.enaium.fabricpvz.portable.lawn.CoinMotion;
import cn.enaium.fabricpvz.portable.lawn.CoinType;
import cn.enaium.fabricpvz.portable.lawn.HelmType;
import cn.enaium.fabricpvz.portable.lawn.Plant;
import cn.enaium.fabricpvz.portable.lawn.PlantSubClass;
import cn.enaium.fabricpvz.portable.lawn.PlantingReason;
import cn.enaium.fabricpvz.portable.lawn.ReanimationType;
import cn.enaium.fabricpvz.portable.pvzp.reanim.Reanimation;
import cn.enaium.fabricpvz.portable.sexy.Rect;
import net.fabricmc.api.ModInitializer;

/**
 * The content mod: two custom plants and one custom zombie, registered through the public content
 * API only.
 *
 * <table>
 *   <caption>Content</caption>
 *   <tr><th>name</th><th>type</th><th>stats</th><th>behaviour hooks</th></tr>
 *   <tr>
 *     <td>Sniper Pea</td><td>plant</td>
 *     <td>200 sun, 500 hp, fires every 100 ticks (peashooter: 150)</td>
 *     <td>whole-lane attack rectangle; cannot be planted in the column the zombies walk in</td>
 *   </tr>
 *   <tr>
 *     <td>Sun Orchid</td><td>plant</td>
 *     <td>25 sun, 350 hp, produces a sun every 2500 ticks</td>
 *     <td>drops a collectable sun on its own cadence; slightly wider collision rectangle</td>
 *   </tr>
 *   <tr>
 *     <td>Bulwark Zombie</td><td>zombie</td>
 *     <td>400 hp, 1500 hp pail, 0.92 render scale, 20px longer reach, 3 wave points</td>
 *     <td>recoloured torso image; bolts its pail back on while it is not eating</td>
 *   </tr>
 * </table>
 *
 * <p>Everything is plain Java: the mod's own lambdas implement the callbacks, nothing is reflected
 * and no game class is patched to register content (the single mixin only keeps the mod's shipped
 * packet art in the game's resource table, see {@link ContentModAssets}).
 */
public final class ContentMod implements ModInitializer {

    /** Mod id, matching {@code fabric.mod.json}. */
    public static final String MOD_ID = "pvz-content-mod";

    // ---------------------------------------------------------------- Sniper Pea

    /** Sun cost of one Sniper Pea seed packet. */
    public static final int SNIPER_PEA_COST = 200;
    /** Recharge time of the seed packet in centiseconds. */
    public static final int SNIPER_PEA_REFRESH = 750;
    /** Ticks between shots; the peashooter's own launch rate is 150. */
    public static final int SNIPER_PEA_LAUNCH_RATE = 100;
    /** Maximum health; the upstream default is 300 and the peashooter's is 300. */
    public static final int SNIPER_PEA_HEALTH = 500;
    /** Width of the attack rectangle in board pixels; upstream shooters use 800. */
    public static final int SNIPER_PEA_ATTACK_WIDTH = 1200;
    /** Highest column the Sniper Pea may be planted in; it needs a firing lane in front of it. */
    public static final int SNIPER_PEA_MAX_COLUMN = 7;

    // ---------------------------------------------------------------- Sun Orchid

    /** Sun cost of one Sun Orchid seed packet. */
    public static final int SUN_ORCHID_COST = 25;
    /** Recharge time of the seed packet in centiseconds. */
    public static final int SUN_ORCHID_REFRESH = 750;
    /** Maximum health. */
    public static final int SUN_ORCHID_HEALTH = 350;
    /** Ticks between two suns; the sunflower's own launch rate is 2500. */
    public static final int SUN_ORCHID_PRODUCE_TICKS = 2500;
    /** How much wider than the plant itself the orchid's collision rectangle is. */
    public static final int SUN_ORCHID_RECT_GROWTH = 12;

    // ---------------------------------------------------------------- Bulwark Zombie

    /** Body health; a plain retail zombie has 270. */
    public static final int BULWARK_HEALTH = 400;
    /** Pail hit points; the retail pail zombie's pail has 1100. */
    public static final int BULWARK_HELM_HEALTH = 1500;
    /** Ticks between two pail repairs. */
    public static final int BULWARK_HELM_REPAIR_TICKS = 100;
    /** Render scale; walk speed follows the reanimation's ground velocity times this scale. */
    public static final float BULWARK_SCALE = 0.92f;
    /** Eating rectangle x offset; the plain zombie's is 50, so this one reaches 20px further. */
    public static final int BULWARK_ATTACK_RECT_X = 70;
    /** Eating rectangle width; unchanged from the plain zombie. */
    public static final int BULWARK_ATTACK_RECT_WIDTH = 20;

    // ---------------------------------------------------------------- registered content

    /** The registered Sniper Pea. */
    public static PlantType sniperPea;
    /** The registered Sun Orchid. */
    public static PlantType sunOrchid;
    /** The registered Bulwark Zombie. */
    public static ZombieType bulwarkZombie;

    /** Ticks the mod's plant callbacks ran; the harness reads these to prove the hooks are reached. */
    public static int orchidTickCallbacks;
    /** Suns the Sun Orchid dropped. */
    public static int sunsProduced;
    /** Zombie ticks the mod's tick hook ran. */
    public static int zombieTickCallbacks;
    /** Zombies that got the recoloured torso. */
    public static int zombieTorsosRecoloured;
    /** Pail repairs the zombie's tick hook performed. */
    public static int helmRepairs;

    @Override
    public void onInitialize() {
        // The mod's own art ships in the jar and has to sit in the game's resource table before the
        // seed packets draw. Installing needs a running application (the images register themselves
        // with it), which only exists later, so the INITIALIZED listener does the real work and the
        // per-tick listener is the safety net for everything that overwrites the ids afterwards.
        ContentModAssets.installPacketArt();
        GameLifecycleEvents.INITIALIZED.register(ContentModAssets::installPacketArt);
        LawnEvents.TICK.register((board, tick) -> ContentModAssets.reapplyOverrides());

        registerSniperPea();
        registerSunOrchid();
        registerBulwarkZombie();
    }

    /**
     * A shooter that is slower to afford than a repeater, fires more often than a peashooter, covers
     * the whole lane and needs room in front of it.
     */
    private static void registerSniperPea() {
        sniperPea = PlantType.builder("sniper_pea")
                .name("Sniper Pea")
                .tooltip("Fires hardened peas down the whole lane.")
                .almanacDescription("Sniper Pea is a marksman. Its hardened peas reach any zombie in "
                        + "the lane, and it fires faster than a repeater - but it is expensive, and it "
                        + "refuses to be planted in the column the zombies walk in.")
                .cost(SNIPER_PEA_COST)
                .refreshTime(SNIPER_PEA_REFRESH)
                .reanimation(ReanimationType.REANIM_PEASHOOTER)
                .subClass(PlantSubClass.SUBCLASS_SHOOTER)
                .launchRate(SNIPER_PEA_LAUNCH_RATE)
                .health(SNIPER_PEA_HEALTH)
                .packetArt(ContentModAssets.PACKET_IMAGE_SNIPER_PEA, 0)
                .plantAttackRect((plant, theWeapon) ->
                        new Rect(plant.mX, plant.mY, SNIPER_PEA_ATTACK_WIDTH, plant.mHeight))
                .plantingRule((board, gridX, gridY, reason) ->
                        gridX > SNIPER_PEA_MAX_COLUMN ? PlantingReason.PLANTING_NOT_HERE : reason)
                .register();
    }

    /**
     * A cheap sun producer that drops its sun on its own cadence, driven by the plant's own launch
     * counter so every orchid keeps its own timer.
     */
    private static void registerSunOrchid() {
        sunOrchid = PlantType.builder("sun_orchid")
                .name("Sun Orchid")
                .tooltip("Produces extra sun, and takes up a little more room.")
                .almanacDescription("Sun Orchid is a bargain: half the price of a sunflower for a "
                        + "little less health. It produces sun at the sunflower's pace and its broad "
                        + "leaves make it easier for zombies to grab.")
                .cost(SUN_ORCHID_COST)
                .refreshTime(SUN_ORCHID_REFRESH)
                .reanimation(ReanimationType.REANIM_SUNFLOWER)
                .subClass(PlantSubClass.SUBCLASS_NORMAL)
                .launchRate(SUN_ORCHID_PRODUCE_TICKS)
                .health(SUN_ORCHID_HEALTH)
                .packetArt(ContentModAssets.PACKET_IMAGE_SUN_ORCHID, 0)
                .plantRect(plant -> new Rect(plant.mX - SUN_ORCHID_RECT_GROWTH, plant.mY,
                        plant.mWidth + 2 * SUN_ORCHID_RECT_GROWTH, plant.mHeight))
                .onTick(ContentMod::tickSunOrchid)
                .register();
    }

    /** One orchid tick: count down its own production timer and drop a collectable sun. */
    private static void tickSunOrchid(Plant thePlant) {
        orchidTickCallbacks++;

        if (thePlant.mLaunchCounter > 0) {
            thePlant.mLaunchCounter--;
        }
        if (thePlant.mLaunchCounter > 0) {
            return;
        }

        thePlant.mLaunchCounter = SUN_ORCHID_PRODUCE_TICKS;

        Board aBoard = thePlant.mBoard;
        if (aBoard == null) {
            return;
        }
        aBoard.addCoin(thePlant.mX + 40, thePlant.mY - 20, CoinType.COIN_SUN, CoinMotion.COIN_MOTION_FROM_PLANT);
        sunsProduced++;
    }

    /**
     * A heavier pail zombie: more body health, a bolted-on pail that repairs itself while the zombie
     * is not eating, a slightly smaller (and therefore slower) walk and a longer reach.
     *
     * <p>The reanimations are the retail ones: hand-authoring a {@code .reanim} is not feasible for a
     * mod, so the body is {@code REANIM_ZOMBIE} with the recoloured retail torso sprite of
     * {@link ContentModAssets} over it, which gives it its own look while keeping every retail
     * animation.
     */
    private static void registerBulwarkZombie() {
        bulwarkZombie = ZombieType.builder("bulwark_zombie")
                .name("Bulwark Zombie")
                .almanacDescription("Bulwark Zombie wears a pail that somebody bolted on, and it "
                        + "tightens the bolts as it walks: the pail repairs itself while the zombie is "
                        + "not eating. All that hardware makes it heavy, so it lumbers towards your "
                        + "house - until it reaches the lawn, where pure stubbornness takes over.")
                .bodyReanimation(ReanimationType.REANIM_ZOMBIE)
                .health(BULWARK_HEALTH)
                .helm(HelmType.HELMTYPE_PAIL, BULWARK_HELM_HEALTH)
                .attackRect(new Rect(BULWARK_ATTACK_RECT_X, 0, BULWARK_ATTACK_RECT_WIDTH, 115))
                .scale(BULWARK_SCALE)
                .wave(3, 1, 1, 2000)
                .canGoInPool(false)
                .onSpawned(ContentMod::onBulwarkSpawned)
                .onTick(ContentMod::tickBulwark)
                .register();
    }

    /** Gives the freshly spawned zombie its recoloured torso. */
    private static void onBulwarkSpawned(cn.enaium.fabricpvz.portable.lawn.Zombie theZombie) {
        if (theZombie.mApp == null) {
            return;
        }

        Reanimation aBody = theZombie.mApp.reanimationTryToGet(theZombie.mBodyReanimID);
        if (ContentModAssets.applyZombieTorso(aBody)) {
            zombieTorsosRecoloured++;
        }
    }

    /** One zombie tick: bolt the pail back on while the zombie is walking. */
    private static void tickBulwark(cn.enaium.fabricpvz.portable.lawn.Zombie theZombie) {
        zombieTickCallbacks++;

        boolean aCanRepair = theZombie.mHelmType == HelmType.HELMTYPE_PAIL
                && !theZombie.mIsEating
                && theZombie.mHelmHealth > 0
                && theZombie.mHelmHealth < BULWARK_HELM_HEALTH
                && theZombie.mZombieAge % BULWARK_HELM_REPAIR_TICKS == 0;
        if (!aCanRepair) {
            return;
        }

        theZombie.mHelmHealth++;
        helmRepairs++;
    }
}
