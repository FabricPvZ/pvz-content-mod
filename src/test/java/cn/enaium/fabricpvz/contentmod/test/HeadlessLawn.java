package cn.enaium.fabricpvz.contentmod.test;

import cn.enaium.fabricpvz.portable.lawn.LawnApp;
import cn.enaium.fabricpvz.portable.lawn.ReanimationType;
import cn.enaium.fabricpvz.portable.lawn.Resources;
import cn.enaium.fabricpvz.portable.lawn.ZenGarden;
import cn.enaium.fabricpvz.portable.lawn.system.Music;
import cn.enaium.fabricpvz.portable.lawn.system.ProfileMgr;
import cn.enaium.fabricpvz.portable.pvzp.PvzpResourceManager;
import cn.enaium.fabricpvz.portable.pvzp.fx.EffectSystem;
import cn.enaium.fabricpvz.portable.pvzp.fx.PvzpFoley;
import cn.enaium.fabricpvz.portable.pvzp.fx.PvzpParticleDefinitions;
import cn.enaium.fabricpvz.portable.pvzp.fx.TrailDefinitions;
import cn.enaium.fabricpvz.portable.pvzp.reanim.Reanimation;
import cn.enaium.fabricpvz.portable.pvzp.reanim.Reanimator;
import cn.enaium.fabricpvz.portable.pvzp.reanim.ReanimatorTrackInstance;
import cn.enaium.fabricpvz.portable.sexy.SexyAppBase;
import cn.enaium.fabricpvz.portable.sexy.graphics.Font;
import cn.enaium.fabricpvz.portable.sexy.graphics.ImageFont;
import cn.enaium.fabricpvz.portable.sexy.graphics.MemoryImage;
import cn.enaium.fabricpvz.portable.sexy.sound.DummyMusicInterface;
import cn.enaium.fabricpvz.portable.sexy.sound.SoundInstance;
import cn.enaium.fabricpvz.portable.sexy.sound.SoundManager;

/**
 * Headless {@link LawnApp} fixture for the end-to-end run.
 *
 * <p>{@code LawnApp}'s constructor does no platform work, but everything downstream (boards, widgets,
 * entity constructors) reads the services {@code LawnApp::Init} and the loading thread create: the
 * profile manager, the effect system, the audio facades, the definition tables and the loaded image
 * and font resources. The game's own test suites fill those in with a shared fixture; a mod project
 * cannot use that fixture (it lives in the game's test sources, which are not published), so the
 * parts this harness needs are replicated here, exactly as the game's
 * {@code cn.enaium.fabricpvz.portable.test.HeadlessTestApp} does.
 */
public final class HeadlessLawn {

    private HeadlessLawn() {
    }

    /** Gives a headless app everything the real init sequence would have created. */
    public static void initialize(LawnApp theApp) {
        // A synthetic resource set must not run the game's group extractor: it would try to bind
        // resources a test pak does not contain.
        PvzpResourceManager.gExtractResourcesByName = null;

        LawnApp.gLawnApp = theApp;

        if (theApp.mZenGarden == null) {
            theApp.mZenGarden = new ZenGarden();
        }
        if (theApp.mProfileMgr == null) {
            theApp.mProfileMgr = new ProfileMgr();
        }
        if (theApp.mPlayerInfo == null) {
            // LoadingThreadProc picks a profile in the real game; headless boards need one too.
            theApp.mPlayerInfo = theApp.mProfileMgr.addProfile("HeadlessTest");
        }
        if (theApp.mEffectSystem == null) {
            theApp.mEffectSystem = new EffectSystem();
            theApp.mEffectSystem.effectSystemInitialize();
        }
        if (theApp.mMusicInterface == null) {
            // Headless runs must not open an audio device.
            theApp.mMusicInterface = new DummyMusicInterface();
        }
        if (theApp.mSoundManager == null) {
            theApp.mSoundManager = new StubSoundManager();
        }
        if (theApp.mMusic == null) {
            theApp.mMusic = new Music();
        }
        if (theApp.mSoundSystem == null) {
            theApp.mSoundSystem = new PvzpFoley();
        }

        PvzpFoley.pvzpFoleyInitialize(PvzpFoley.gLawnFoleyParamArray, PvzpFoley.gLawnFoleyParamArray.length);
        Reanimator.reanimatorLoadDefinitions(Reanimator.gLawnReanimationArray, ReanimationType.NUM_REANIMS);
        PvzpParticleDefinitions.pvzpParticleLoadDefinitions(
                PvzpParticleDefinitions.gLawnParticleArray,
                PvzpParticleDefinitions.gLawnParticleArray.length);
        TrailDefinitions.trailLoadDefinitions(
                TrailDefinitions.gLawnTrailArray, TrailDefinitions.gLawnTrailArray.length);

        bindStubImages(theApp);
        bindStubFonts(theApp);
        theApp.mDefaultFont = Resources.getFontById(Resources.FONT_PICO129_ID);
    }

    /** Binds a 1x1 stub image for every image resource id, so drawing and measuring never NPEs. */
    public static void bindStubImages(SexyAppBase theApp) {
        for (Resources.ResourceEntry anEntry : Resources.ENTRIES) {
            if (anEntry.type() == Resources.ResourceType.IMAGE) {
                MemoryImage aStub = new MemoryImage(theApp);
                aStub.create(1, 1);
                Resources.setImageById(anEntry.id(), aStub);
            }
        }
    }

    /** Binds a stub font for every font resource id. */
    public static void bindStubFonts(SexyAppBase theApp) {
        for (Resources.ResourceEntry anEntry : Resources.ENTRIES) {
            if (anEntry.type() == Resources.ResourceType.FONT) {
                Resources.setFontById(anEntry.id(), newStubFont(theApp));
            }
        }
    }

    /** A fresh headless bitmap font: measures as zero width, never dereferences a null glyph sheet. */
    public static Font newStubFont(SexyAppBase theApp) {
        MemoryImage aGlyphSheet = new MemoryImage(theApp);
        aGlyphSheet.create(1, 8);
        return new ImageFont(aGlyphSheet);
    }

    /** Track count of a stubbed reanimation; covers the largest retail zombie/plant reanimation. */
    public static final int STUB_REANIM_TRACK_COUNT = 64;

    /**
     * Makes a reanimation that could not load retail {@code .reanim} data look loaded: entity code
     * indexes its track instances unconditionally, so the array has to exist and be fully
     * initialised.
     */
    public static void stubLoadedTracks(Reanimation theReanim) {
        if (theReanim == null || theReanim.mTrackInstances != null) {
            return;
        }

        theReanim.mTrackInstances = new ReanimatorTrackInstance[STUB_REANIM_TRACK_COUNT];
        for (int aTrackIndex = 0; aTrackIndex < STUB_REANIM_TRACK_COUNT; aTrackIndex++) {
            theReanim.mTrackInstances[aTrackIndex] = new ReanimatorTrackInstance();
        }
    }

    /** A {@link LawnApp} whose reanimations look loaded, the way the retail assets would. */
    public static final class LoadedReanimApp extends LawnApp {

        @Override
        public Reanimation addReanimation(float theX, float theY, int theRenderOrder, int theReanimationType) {
            Reanimation aReanim = super.addReanimation(theX, theY, theRenderOrder, theReanimationType);
            stubLoadedTracks(aReanim);
            return aReanim;
        }
    }

    /** Silent {@link SoundManager} double: headless runs must not open an audio device. */
    public static final class StubSoundManager implements SoundManager {

        private double mMasterVolume = 0.5525;

        @Override
        public boolean initialized() {
            return true;
        }

        @Override
        public boolean loadSound(long theSfxID, String theFilename) {
            return true;
        }

        @Override
        public long loadSound(String theFilename) {
            return 0;
        }

        @Override
        public void releaseSound(long theSfxID) {
        }

        @Override
        public void setVolume(double theVolume) {
        }

        @Override
        public boolean setBaseVolume(long theSfxID, double theBaseVolume) {
            return true;
        }

        @Override
        public boolean setBasePan(long theSfxID, int theBasePan) {
            return true;
        }

        @Override
        public SoundInstance getSoundInstance(long theSfxID) {
            return new StubSoundInstance();
        }

        @Override
        public void releaseSounds() {
        }

        @Override
        public void releaseChannels() {
        }

        @Override
        public double getMasterVolume() {
            return mMasterVolume;
        }

        @Override
        public void setMasterVolume(double theVolume) {
            mMasterVolume = theVolume;
        }

        @Override
        public void flush() {
        }

        @Override
        public void stopAllSounds() {
        }

        @Override
        public long getFreeSoundId() {
            return 0;
        }

        @Override
        public int getNumSounds() {
            return 0;
        }
    }

    /** Silent {@link SoundInstance} double. */
    public static final class StubSoundInstance implements SoundInstance {

        private double mVolume = 1.0;
        private boolean mPlaying;

        @Override
        public void release() {
        }

        @Override
        public void setBaseVolume(double theBaseVolume) {
        }

        @Override
        public void setBasePan(int theBasePan) {
        }

        @Override
        public void adjustPitch(double theNumSteps) {
        }

        @Override
        public void setVolume(double theVolume) {
            mVolume = theVolume;
        }

        @Override
        public void setPan(int thePosition) {
        }

        @Override
        public boolean play(boolean looping, boolean autoRelease) {
            mPlaying = true;
            return true;
        }

        @Override
        public void stop() {
            mPlaying = false;
        }

        @Override
        public boolean isPlaying() {
            return mPlaying;
        }

        @Override
        public boolean isReleased() {
            return false;
        }

        @Override
        public double getVolume() {
            return mVolume;
        }
    }
}
