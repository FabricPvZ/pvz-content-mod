# pvz-content-mod

A complete, player-installable content mod for the PvZ port: **two custom plants** and **one custom
zombie**, registered through the published content API
(`cn.enaium.fabricpvz:api-plant:1.0.0`, `cn.enaium.fabricpvz:api-zombie:1.0.0`,
`cn.enaium.fabricpvz:api-events:1.0.0`), with their own generated art and behaviour hooks.

It is a plain Fabric mod: `fabric.mod.json`, a `ModInitializer`, one small Mixin, and a jar that is
loaded from `<gamedir>/mods` by the real Fabric Loader through
`cn.enaium.fabricpvz:fabric-provider` (the loader's `GameProvider` for this port).

| mod id | version | entry point |
|---|---|---|
| `pvz-content-mod` | `1.0.0` | `cn.enaium.fabricpvz.contentmod.ContentMod` |

## What it adds

### Sniper Pea (`sniper_pea`) - plant

| stat | value | upstream comparison |
|---|---|---|
| sun cost | 200 | peashooter 100, repeater 200 |
| packet recharge | 750 cs (short) | peashooter 750 |
| launch rate | **100 ticks** | peashooter 150, repeater 150 |
| health | 500 | peashooter 300 |
| reanimation | `REANIM_PEASHOOTER` (retail art, reused) | |
| packet art | `assets/pvz-content-mod/textures/packet_sniper_pea.png` (generated) | |
| subclass | `SUBCLASS_SHOOTER` | |

Behaviour: it fires faster than a repeater, and its **attack rectangle covers the whole lane**
(1200 px instead of the shooter default of 800 px), so it hits any zombie in its row. It also pins a
**planting rule**: it refuses the column the zombies walk in (column 8), because it wants a firing
lane in front of it.

### Sun Orchid (`sun_orchid`) - plant

| stat | value | upstream comparison |
|---|---|---|
| sun cost | 25 | sunflower 50 |
| packet recharge | 750 cs (short) | sunflower 750 |
| production | a collectable sun every **2500 ticks** | sunflower 2500 |
| health | 350 | sunflower 300 |
| reanimation | `REANIM_SUNFLOWER` (retail art, reused) | |
| packet art | `assets/pvz-content-mod/textures/packet_sun_orchid.png` (generated) | |
| subclass | `SUBCLASS_NORMAL` | |

Behaviour: it drives its own production timer through the plant's own launch counter (so every orchid
keeps its own cadence, exactly like a sunflower) and drops a real `COIN_SUN` on the board; its
**collision rectangle is 12 px wider on each side** (broad leaves make it easier to grab).

### Bulwark Zombie (`bulwark_zombie`) - zombie

| stat | value | upstream comparison |
|---|---|---|
| body health | 400 | plain zombie 270 |
| pail | `HELMTYPE_PAIL`, 1500 hp | retail pail zombie 1100 |
| eat rectangle | `x=70, w=20` | plain zombie `x=50, w=20` (20 px longer reach) |
| render scale | 0.92 (walks ~8 % slower: the walk speed is the animation's ground velocity times this scale) | plain zombie 1.0 |
| wave points | 3 (level 1, from wave 1, pick weight 2000) | plain zombie 1 |
| body reanimation | `REANIM_ZOMBIE` (retail art, reused) | |
| torso art | `assets/pvz-content-mod/textures/zombie_torso.png` (generated) | |

Behaviour: on spawn it gets a **recoloured torso** (the retail `Zombie_body` track image is replaced,
so it keeps every retail animation but has its own colour), and on every tick it **bolts its pail back
on**: while it is not eating, the pail repairs 1 hp every 100 ticks.

## How the content is registered

All of it is plain Java in `ContentMod#onInitialize` - no reflection, no generated code:

```java
public final class ContentMod implements net.fabricmc.api.ModInitializer {

    @Override
    public void onInitialize() {
        registerSniperPea();
        registerSunOrchid();
        registerBulwarkZombie();
    }

    private static void registerSniperPea() {
        sniperPea = PlantType.builder("sniper_pea")
                .name("Sniper Pea")
                .tooltip("Fires hardened peas down the whole lane.")
                .almanacDescription("Sniper Pea is a marksman. ...")
                .cost(200)
                .refreshTime(750)
                .reanimation(ReanimationType.REANIM_PEASHOOTER)
                .subClass(PlantSubClass.SUBCLASS_SHOOTER)
                .launchRate(100)
                .health(500)
                .packetArt(ContentModAssets.PACKET_IMAGE_SNIPER_PEA, 0)
                .plantAttackRect((plant, weapon) -> new Rect(plant.mX, plant.mY, 1200, plant.mHeight))
                .plantingRule((board, gridX, gridY, reason) ->
                        gridX > SNIPER_PEA_MAX_COLUMN ? PlantingReason.PLANTING_NOT_HERE : reason)
                .register();
    }

    private static void registerSunOrchid() {
        sunOrchid = PlantType.builder("sun_orchid")
                .name("Sun Orchid")
                .cost(25)
                .reanimation(ReanimationType.REANIM_SUNFLOWER)
                .launchRate(2500)
                .health(350)
                .packetArt(ContentModAssets.PACKET_IMAGE_SUN_ORCHID, 0)
                .plantRect(plant -> new Rect(plant.mX - 12, plant.mY, plant.mWidth + 24, plant.mHeight))
                .onTick(ContentMod::tickSunOrchid)      // drops a COIN_SUN on its own cadence
                .register();
    }

    private static void registerBulwarkZombie() {
        bulwarkZombie = ZombieType.builder("bulwark_zombie")
                .name("Bulwark Zombie")
                .bodyReanimation(ReanimationType.REANIM_ZOMBIE)   // retail reanimation, reused
                .health(400)
                .helm(HelmType.HELMTYPE_PAIL, 1500)
                .attackRect(new Rect(70, 0, 20, 115))
                .scale(0.92f)
                .wave(3, 1, 1, 2000)
                .onSpawned(ContentMod::onBulwarkSpawned)          // recoloured torso image override
                .onTick(ContentMod::tickBulwark)                  // repairs the pail every 100 ticks
                .register();
    }
}
```

Once `register()` returns, the plant or zombie is a normal piece of game content: the seed packet,
the almanac, `Board#canPlantAt`, `Board#addPlant`, the wave composer and `Board#addZombieInRow` all
treat it like a built-in. Clients of the API can also observe it from outside via
`PlantRegistry`/`ZombieRegistry` and the `CustomPlantEvents`/`CustomZombieEvents` events.

## The art

The three PNGs are generated from the player's own retail sprites - no hand-drawn placeholders:

```bash
python3 tools/generate_assets.py                 # auto-detects the Steam install
python3 tools/generate_assets.py --game-dir "<the directory with main.pak>"
python3 tools/generate_assets.py --check         # verify the committed PNGs are up to date
```

| output | source sprite (from `main.pak`) | operation |
|---|---|---|
| `packet_sniper_pea.png` (72x70) | `reanim/PeaShooter_Head.png` | hue rotate +265°, saturation x1.15, centred on the retail 72x70 packet-canvas |
| `packet_sun_orchid.png` (72x70) | `reanim/SunFlower_head.png` | hue rotate +155°, saturation x1.25, scaled x1.35, centred |
| `zombie_torso.png` (53x63) | `reanim/Zombie_body.png` | hue rotate +150°, saturation x1.10 (same size, it replaces a reanimation track) |

The packet icons are 72x70 because that is the cel size of the retail `images/packet_plants.png`
sheet, which the game draws through the very same call (`PvzpCommon::PvzpDrawImageCelScaledF`) as a
mod plant's fixed packet art.

How the images reach the game:

* **packet art** - `PlantType.Builder#packetArt(imageId, cel)` takes an `ImageID`, i.e. an index into
  the game's own resource table, and the API has no image id range reserved for mods. The mod
  therefore uses two retail ids that this port *never reads* (`IMAGE_PEA_SPLATS` = 218 and
  `IMAGE_POTATOMINE_PARTICLES` = 226, both in the `LoadingImages` group) and installs its own PNGs
  into them with `Resources.setImageById`. `ContentModAssets` loads the PNGs from the mod jar and
  `ResourcesMixin` re-installs them after every resource group load - the game binds a group by
  overwriting the ids of that group, and the published API has no "resources loaded" event, which is
  why the mod supplies that one small Mixin itself. A per-tick safety net (`LawnEvents.TICK`) covers
  the same ground if a loader ever refuses the Mixin.
* **zombie art** - `Reanimation#setImageOverride("Zombie_body", image)` replaces the torso image of
  the zombie's retail reanimation on spawn. Hand-authoring a `.reanim` is not something a mod can
  reasonably do, so the zombie reuses the retail `REANIM_ZOMBIE` animation and only swaps the art.

## Build

```bash
./gradlew build
```

produces `build/libs/pvz-content-mod-1.0.0.jar`, the installable mod jar (metadata, classes, mixin
config and the three PNGs). `./gradlew publishToMavenLocal` publishes
`cn.enaium.fabricpvz:pvz-content-mod:1.0.0` if another project wants to depend on it.

The loader, the Mixin runtime, ASM, the game and the API modules are `compileOnly`: a mod jar never
bundles them, they come from the installation at runtime. They are not declared here either — this
project's whole build file is the toolchain plugin plus its coordinates:

```kotlin
plugins { id("cn.enaium.fabricpvz.loom") version "1.0.0" }
group = "cn.enaium.fabricpvz"
version = "1.0.0"
```

`cn.enaium.fabricpvz.loom` (from the Gradle Plugin Portal, so this project needs nothing published
locally first) puts the real Fabric Loader, Mixin, ASM, the API and the game on the
compile and test class paths, wires the end-to-end test's launcher class path, publishes the jar, and
adds `./gradlew runClient`, which boots the game through the real loader with the built jar in
`run/mods/`.

## Install

```
<game directory>/            <- the directory the launcher uses as -Dpvz.gameDir
├── mods/
│   ├── pvz-content-mod-1.0.0.jar
│   └── fabric-api-1.0.0.jar   # the API: one jar, its four modules nested inside it
└── userdata/
```

Copy the built jar into `<gamedir>/mods` (or point Fabric Loader at another directory with
`-Dfabric.modsFolder=<dir>`) and start the game through Fabric Loader's `KnotClient` as usual. The
mod needs `pvz-portable`, `api-base`, `api-events`, `api-plant` and `api-zombie` to be
present; one jar provides all four API mods - `fabric-api-1.0.0.jar`, the aggregate the toolchain
depends on and whose nested jars under `META-INF/jars/` are the modules (`loom/README.md`,
"Jar-in-jar") - and `pvz-portable` is the game itself.

## Verification

```bash
./gradlew test
```

The test copies the built jar into a temporary `mods/` directory and starts the **real Fabric Loader**
in a second JVM (`net.fabricmc.loader.impl.launch.knot.KnotClient`, with the class path that does not
contain this project's own build output, so the mod can only come from `mods/`). The harness entry
point is handed to the loader with `-Dpvz.extraClasspath=<test classes>` so the loader defines the
harness, the mod, the API and the game with one class loader - otherwise the assertions would read a
second copy of every static. The loader resolves the mod, runs its `ModInitializer`, applies its Mixin
configuration and then calls the game entry point, which the test redirects to `ContentModGameMain`.
That harness reports, and the test asserts:

* both plants and the zombie are registered and the game's own definition tables (`Plant#getPlantDefinition`,
  `Zombie#getZombieDefinition`) report the registered stats, names, tooltips and almanac text;
* both packet images were loaded from the mod jar (72x70, real pixels), sit in the game's resource
  table, and are re-installed after a resource group load (the Mixin ran: `mixinCallbacks >= 1`) and by
  the per-tick safety net;
* on a real headless board both plants can be placed with their stats, the Sniper Pea's whole-lane
  attack rectangle and planting rule are in effect, the Sun Orchid drops a real sun coin, the Bulwark
  Zombie spawns with its health, pail, scale and longer reach, gets its recoloured torso, walks, takes
  its pail repair and dies to a lethal hit;
* the run ends with the game still in `GameScenes.SCENE_PLAYING` and no exception anywhere.

When this machine has the retail assets, the harness also mounts the real `main.pak` and checks that
the two image ids the mod uses are real retail resources (`particles/pea_splats.png`,
`particles/potatomine_particles.png`).

## License

MIT (see `LICENSE`). The game port itself (`PvZ-Portable`) is LGPL-3.0-or-later with the PopCap framework licence.
