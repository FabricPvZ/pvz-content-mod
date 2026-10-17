package cn.enaium.fabricpvz.contentmod;

import java.util.IdentityHashMap;
import java.util.Map;

import cn.enaium.fabricpvz.api.lawn.LawnEvents;
import cn.enaium.fabricpvz.api.lifecycle.GameLifecycleEvents;
import cn.enaium.fabricpvz.api.resource.GameArt;
import cn.enaium.fabricpvz.api.resource.ModImages;
import cn.enaium.fabricpvz.api.resource.ModReanims;
import cn.enaium.fabricpvz.portable.lawn.Plant;
import cn.enaium.fabricpvz.portable.lawn.ReanimationID;
import cn.enaium.fabricpvz.portable.lawn.SeedType;
import cn.enaium.fabricpvz.portable.pvzp.reanim.Reanimation;
import cn.enaium.fabricpvz.portable.sexy.SexyAppBase;
import cn.enaium.fabricpvz.portable.sexy.graphics.Image;
import cn.enaium.fabricpvz.portable.sexy.graphics.MemoryImage;

/**
 * The red plants' art, i.e. this mod's <em>choice</em>: {@code api-resource} ships the game's own
 * painters ({@link GameArt}), the image helpers ({@link ModImages}) and the per-track recolouring
 * ({@link ModReanims}); this class says that each red plant is its retail twin's packet art and
 * reanimation with a hue mapping, and which numbers that mapping means.
 *
 * <p>Nothing of the art is hand-drawn or shipped as a file. Each icon is the <em>game's own</em>
 * packet art of the retail seed the plant copies, rendered by {@link GameArt#packetArt(int)} (at that
 * seed's own packet placement) and re-mapped to red, and each board plant is the retail reanimation's
 * own images re-mapped the same way. A copy of the retail art cannot drift from the plant it depicts,
 * which is what a hand-rendered icon would do.
 *
 * <p>The icons are handed to the game as a {@linkplain cn.enaium.fabricpvz.api.plant.PlantType.Builder#packetArt(java.util.function.Supplier)
 * lazy supplier} ({@link #packetImage(String)}) rather than parked under a claimed resource id: the
 * icon is built from art that loads after this mod's entry point, and the seed chooser draws its
 * packets before any board tick runs, so the drawing has to be able to ask again - which is also what
 * keeps the icon out of reach of the game's resource group re-loads (they own the image table).
 */
public final class RedPlantsArt {

    /**
     * Hue the peashooter family's own greens map onto red, in degrees. The plants are yellow-green
     * (roughly 60-180 degrees), so pivoting there is what lands the head, the stalk and the leaves on
     * red instead of on the magenta a plain -120 degree rotation produces. All three plants of this
     * family share it, so the board plant and the seed packet are coloured alike.
     */
    private static final float RED_PIVOT_HUE = 90.0f;

    /**
     * How far the hues are compressed around {@link #RED_PIVOT_HUE}: one quarter turns the whole
     * 60-180 degree band into 352-22 degrees, i.e. red, while keeping hue differences - and therefore
     * the shading of the art - intact.
     */
    private static final float RED_HUE_COMPRESSION = 0.25f;

    /** Last asset loading problem, empty when every asset loaded. */
    public static String loadError = "";

    /** How many track images were replaced by their red copy, over every plant of every kind. */
    public static int recolours;

    /** How many plants got at least one track recoloured (one per placed red plant with retail art). */
    public static int plantedPlants;

    /**
     * Red copy of every source track image that was recoloured, keyed by <em>identity</em>: the
     * reanimation definitions are process-global, so every red plant of a board hands in the very same
     * {@link Image} instances and one red copy serves all of them.
     */
    private static final Map<Image, Image> redImages = new IdentityHashMap<>();

    /** One plant's art: the retail seed it copies, and the images built from that seed's own art. */
    private static final Art PEASHOOTER = new Art(SeedType.SEED_PEASHOOTER);
    private static final Art REPEATER = new Art(SeedType.SEED_REPEATER);
    private static final Art GATLING = new Art(SeedType.SEED_GATLINGPEA);
    private static final Art[] PLANTS = {PEASHOOTER, REPEATER, GATLING};

    private RedPlantsArt() {
    }

    /**
     * Registers the mod's three call points: build the art once the game has an application
     * ({@link GameLifecycleEvents#INITIALIZED}), retry from the tick hook until it can be built (the
     * art it is made of loads after the mod's entry point), and recolour the blink reanimation the
     * game allocates per blink.
     *
     * <p>The packet icons are not built here: the game asks for them through the supplier the plants
     * were registered with ({@link #packetImage(String)}), once per packet draw, so a packet drawn
     * before this mod's art is loaded is simply drawn by the game and the next one shows the icon.
     */
    static void install() {
        GameLifecycleEvents.INITIALIZED.register(RedPlantsArt::installArt);
        LawnEvents.TICK.register((board, tick) -> installArt());
    }

    /**
     * The packet art of the plant registered under {@code thePlantId}: the retail twin's own packet
     * art, re-mapped to red. Built on the first call that finds the retail art loaded and remembered
     * afterwards, so the game's per-draw questions cost nothing once the icon exists.
     *
     * @param thePlantId the id the plant was registered with
     * @return the icon, or {@code null} while the resources it draws from are not loaded yet (the game
     *     then draws the packet itself); the next call asks again
     * @throws IllegalArgumentException for a plant this mod has no art for
     */
    public static Image packetImage(String thePlantId) {
        Art aArt = artOf(thePlantId);
        if (aArt.packet == null) {
            aArt.packet = makeRedPacket(aArt.retailSeedType);
        }
        return aArt.packet;
    }

    /**
     * Builds the art if the game is ready for it and hands the cached frames to the game: each red
     * cached frame goes into the game's plant-frame cache. Idempotent - the tick hook calls it every
     * tick, so everything in here may run again and again.
     */
    private static void installArt() {
        if (SexyAppBase.current == null) {
            return;
        }

        for (Art aArt : PLANTS) {
            installCachedPlantFrame(aArt);
        }
    }

    /**
     * Gives the registered seed type the red cached plant frame - the picture the cursor preview, the
     * zen garden and every other reader of the game's plant-frame cache shows. It is the retail twin's
     * own cached frame re-mapped like the planted plant, because the frame is drawn from the
     * reanimation's own images and knows nothing about the per-plant recolouring: without this the
     * plant would be red on the lawn and green under the mouse pointer.
     *
     * <p>Quietly does nothing while the frame cannot be rendered yet (the art it is made of is not
     * loaded); the tick hook asks again every tick until it can be.
     */
    private static void installCachedPlantFrame(Art theArt) {
        cn.enaium.fabricpvz.api.plant.PlantType aType = ContentMod.plantCopying(theArt.retailSeedType);
        if (aType == null) {
            return;
        }

        if (theArt.frame == null) {
            MemoryImage aRetail = GameArt.cachedPlantFrame(theArt.retailSeedType);
            if (aRetail == null) {
                return;
            }
            theArt.frame = ModImages.remapHue(aRetail, RED_PIVOT_HUE, RED_HUE_COMPRESSION);
        }

        if (theArt.frame != null) {
            GameArt.installCachedPlantFrame(aType.seedType(), theArt.frame);
        }
    }

    /**
     * One plant's icon: the retail twin's own packet art (at that seed's placement), re-mapped to red.
     *
     * @return the icon, or {@code null} while the resources it draws from are not loaded yet (the
     *     packet is drawn by the game until it can be built, and the next draw asks again)
     */
    private static MemoryImage makeRedPacket(int theRetailSeedType) {
        MemoryImage aRetail = GameArt.packetArt(theRetailSeedType);
        if (aRetail == null) {
            // The game drew nothing: the art this icon is made of is not loaded yet.
            loadError = "the retail packet art of seed type " + theRetailSeedType + " is not drawable yet";
            return null;
        }

        MemoryImage aRed = ModImages.remapHue(aRetail, RED_PIVOT_HUE, RED_HUE_COMPRESSION);
        if (aRed == null) {
            loadError = "the retail packet art of seed type " + theRetailSeedType + " has no pixel data";
        }
        return aRed;
    }

    /**
     * Turns one plant's retail art red: every track of its body and head reanimations that carries art
     * is replaced by a red copy of the image that track draws, so the plant keeps the retail animation
     * and only changes colour. Registered as the plant's {@code onPlanted} callback.
     *
     * <p>The head reanimation is created here when the plant has none: the game does that only for its
     * own peashooter-family seed types ({@code Plant::PlantInitialize}), and a plant of this family
     * without it would draw leaves and stem and no head at all.
     *
     * @param thePlant the plant that was just placed
     */
    public static void onPlanted(Plant thePlant) {
        if (thePlant == null || thePlant.mApp == null) {
            return;
        }

        Reanimation aBody = thePlant.mApp.reanimationTryToGet(thePlant.mBodyReanimID);
        if (aBody == null || aBody.mTrackInstances == null) {
            // A reanimation without track instances (no retail .reanim data loaded) cannot carry an
            // override; the plant then simply keeps the retail colours.
            return;
        }

        int aRecoloured = ModReanims.overrideTrackArt(aBody, RedPlantsArt::redCopy);
        ModReanims.giveSeparateHeadLayer(thePlant);
        Reanimation aHead = thePlant.mApp.reanimationTryToGet(thePlant.mHeadReanimID);
        if (aHead != null) {
            aRecoloured += ModReanims.overrideTrackArt(aHead, RedPlantsArt::redCopy);
        }

        recolours += aRecoloured;
        if (aRecoloured > 0) {
            plantedPlants++;
        }
    }

    /**
     * Re-applies the red art to the reanimation the game allocates for a blink. {@code Plant::DoBlink}
     * builds that reanimation on demand for every blink, so it never sees the recolouring the plant's
     * planting did; registered as the plant's {@code onTick} callback and quiet while there is no
     * blink (which is almost every tick).
     *
     * @param thePlant a red plant
     */
    public static void onTick(Plant thePlant) {
        if (thePlant == null || thePlant.mApp == null || thePlant.mBlinkReanimID == ReanimationID.REANIMATIONID_NULL) {
            return;
        }

        recolours += ModReanims.overrideTrackArt(
                thePlant.mApp.reanimationTryToGet(thePlant.mBlinkReanimID), RedPlantsArt::redCopy);
    }

    /** @param theImage a track image @return its red copy, made once per source image, or {@code null} */
    private static Image redCopy(Image theImage) {
        Image aCached = redImages.get(theImage);
        if (aCached != null) {
            return aCached;
        }

        if (!(theImage instanceof MemoryImage) || SexyAppBase.current == null) {
            return null;
        }

        MemoryImage aRed = ModImages.remapHue((MemoryImage) theImage, RED_PIVOT_HUE, RED_HUE_COMPRESSION);
        if (aRed != null) {
            redImages.put(theImage, aRed);
        }
        return aRed;
    }

    /** @return the art of the plant registered under {@code thePlantId} */
    private static Art artOf(String thePlantId) {
        for (Art aArt : PLANTS) {
            if (aArt.plantId().equals(thePlantId)) {
                return aArt;
            }
        }
        throw new IllegalArgumentException("no red plant art for '" + thePlantId + "'");
    }

    /** The retail seed a plant copies, and the images built from that seed's own art. */
    private static final class Art {

        private final int retailSeedType;
        private Image packet;
        private MemoryImage frame;

        private Art(int theRetailSeedType) {
            this.retailSeedType = theRetailSeedType;
        }

        /** @return the id of the plant that copies {@link #retailSeedType} */
        private String plantId() {
            return switch (retailSeedType) {
                case SeedType.SEED_PEASHOOTER -> ContentMod.PEASHOOTER_ID;
                case SeedType.SEED_REPEATER -> ContentMod.REPEATER_ID;
                case SeedType.SEED_GATLINGPEA -> ContentMod.GATLING_ID;
                default -> "";
            };
        }
    }
}
