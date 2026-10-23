# pvz-content-mod

A complete, player-installable content mod for the PvZ port: **three custom plants** - the Red
Peashooter, the Red Repeater and the Red Gatling Pea - registered through the published content API
(`api-plant`, `api-events`) with their own generated art, which it places through `api-resource`
instead of implementing the mechanism itself.

It is a plain Fabric mod: `fabric.mod.json`, a `ModInitializer`, and a jar that is loaded from
`<gamedir>/mods` by the real Fabric Loader through the game provider the game jar itself carries (`cn.enaium.fabricpvz.provider`, the
loader's `GameProvider` for this port). **It carries no Mixin**: the packet art reaches the game
through the paint hook `api-plant` ships (the plant's art supplier), and the per-track recolouring
through `api-resource`'s helpers.

This mod is deliberately a **thin example**: it owns what it is (the red peashooter's content) and
what it picks (which tracks, which hue mapping, how the packet icon is built), and depends on the API
for everything else - registration, events, the game's own packet/cached-frame painters, the
per-track overrides.

| mod id | version | entry point |
|---|---|---|
| `pvz-content-mod` | `1.1.0` | `cn.enaium.fabricpvz.contentmod.ContentMod` |

## What it adds

### Red Peashooter (`red_peashooter`) - plant

The peashooter's red twin: the same cadence and health, fire instead of plain peas, and it is a
progression plant rather than a shop item.

| stat | value | upstream comparison |
|---|---|---|
| sun cost | **175** | peashooter 100, snow pea 175, repeater 200 |
| packet recharge | 750 cs (short) | peashooter 750 |
| launch rate | 150 ticks | peashooter 150 |
| health | 300 (not overridden) | peashooter 300 |
| projectile | `PROJECTILE_FIREBALL` (the fire pea) | peashooter `PROJECTILE_PEA` |
| unlock | `.unlockAfter(SEED_SNOWPEA)`: the seed appears once the **Snow Pea**'s award is the player's | snow pea: awarded in the adventure |
| not in the shop | `StoreItem`/`mPurchases` are not involved at all | shop plants are bought |
| seed chooser | page 2, first square (the chooser's own paging) | retail seeds are page 1 |
| almanac | page 2 of the plant grid, first square, with the mod's name and description (`.almanacDescription(...)`) | retail plants are page 1 |
| reanimation | `REANIM_PEASHOOTER` (retail art, recoloured red at runtime) | |
| packet art | the game's own peashooter packet art, rendered and recoloured at runtime (see *The art*) | |
| subclass | `SUBCLASS_SHOOTER` | |

Behaviour:

* **Red art on the board** - on planting, every track of the retail peashooter reanimation that
  carries art (`anim_face`, `idle_mouth`, `anim_sprout`, `anim_blink`, `idle_shoot_blink`,
  `stalk_top`, `stalk_bottom`, the front and back leaves with their tips) is replaced by a red copy of
  the image that track draws, so the plant keeps the whole retail animation and only changes colour.
  The copy is made once per source image and shared by every Red Peashooter on the board. Two pieces
  the game gives only to its own peashooter seed types come from the mod itself: the **head**
  reanimation (the body reanimation plays the leaves and the stalk only) and the art of the **blink**
  reanimation the game allocates per blink, which the plant's tick hook re-colours.
* **Fire peas** - `Plant::Fire` is answered by the API's projectile hook, which fires the registered
  `PROJECTILE_FIREBALL`: the game's own burning pea (40 damage, splash on impact, removes the cold
  effects of what it hits), so the plant shoots like a torchwood-boosted peashooter without one.

### Red Repeater (`red_repeater`) - plant

The repeater's red twin: the same cadence and recharge, two fire peas per volley, and released by the
adventure's own Repeater award.

| stat | value | upstream comparison |
|---|---|---|
| sun cost | **225** | repeater 200 |
| packet recharge | 750 cs (short) | repeater 750 |
| launch rate | 150 ticks | repeater 150 |
| health | 300 (not overridden) | repeater 300 |
| projectile | `PROJECTILE_FIREBALL` | repeater `PROJECTILE_PEA` |
| burst | `.burst(2)`: two peas per volley, on the retail repeater's own timing | repeater fires two |
| unlock | `.unlockAfter(SEED_REPEATER)`: the seed appears once the **Repeater**'s award is the player's | repeater: awarded in the adventure |
| reanimation | `REANIM_REPEATER` (retail art, recoloured red at runtime) | |
| packet art | the game's own repeater packet art, rendered and recoloured at runtime (see *The art*), on the normal packet background | |

### Red Gatling Pea (`red_gatling`) - plant

The gatling pea's red twin: the shop plant's long recharge, four fire peas per volley, and released
the moment the player buys the shop's Gatling Pea (the mod itself never enters the shop - the gate is
the game's own `LawnApp::HasSeedType(SEED_GATLINGPEA)`, which reads the purchase).

| stat | value | upstream comparison |
|---|---|---|
| sun cost | **275** | gatling pea 250 |
| packet recharge | 5000 cs (very long) | gatling pea 5000 |
| launch rate | 150 ticks | gatling pea 150 |
| health | 300 (not overridden) | gatling pea 300 |
| projectile | `PROJECTILE_FIREBALL` | gatling pea `PROJECTILE_PEA` |
| burst | `.burst(4)`: four peas per volley, at the retail gatling pea's counters (68/51/35/18) | gatling pea fires four |
| unlock | `.unlockAfter(SEED_GATLINGPEA)`: the shop purchase releases it | gatling pea is a shop item |
| reanimation | `REANIM_GATLINGPEA` (retail art, recoloured red at runtime) | |
| packet art | the game's own gatling pea packet art, rendered and recoloured at runtime (see *The art*) | |
| packet background | **purple (upgrade)**: `.upgrade(true)` makes `Plant::IsUpgrade` answer `true`, so the game draws this seed on the upgrade packet | gatling pea is a shop upgrade |
| planted on | `.upgradeFrom(SEED_REPEATER, redRepeater.seedType())`: dragging this seed onto a retail **or** red repeater replaces it, like the shop's gatling pea on a repeater (`Plant::IsUpgradableTo`, which only knows the retail pair without this) | gatling pea upgrades the repeater |

Both new plants share everything else with the Red Peashooter: the red art (their retail
reanimation's every art-carrying track re-mapped, plus the head and blink reanimations), the packet
icons (each twin's own packet art, rendered and recoloured at runtime), the fire
peas, and the seed chooser's/almanac's extra page. The red gatling pea is the only one of the three
with the purple upgrade packet. The burst is the one thing a registered shooting
plant has to declare: the game hard-codes the multi-shot timing on its own seed types
(`Plant::FindTargetAndFire`, `UpdateShooter`, `UpdateShooting`), so without `.burst(n)` a registered
plant fires a single pea. See the mod development site (`../docs/`), chapter *Plants and zombies*,
burst section.

## How the content is registered

All of it is plain Java in `ContentMod#onInitialize` - no reflection, no generated code:

```java
public final class ContentMod implements net.fabricmc.api.ModInitializer {

    @Override
    public void onInitialize() {
        RedPlantsArt.install();   // build the cached frames once the game has an app; retry per tick
        registerRedPeashooter();
        registerRedRepeater();
        registerRedGatling();
    }

    private static void registerRedPeashooter() {
        redPeashooter = PlantType.builder("red_peashooter")
                .name("Red Peashooter")
                .tooltip("Fires burning peas instead of plain ones.")
                .cost(175)
                .refreshTime(750)
                .reanimation(ReanimationType.REANIM_PEASHOOTER)
                .subClass(PlantSubClass.SUBCLASS_SHOOTER)
                .launchRate(150)
                .projectile(ProjectileType.PROJECTILE_FIREBALL)
                .unlockAfter(SeedType.SEED_SNOWPEA)
                .packetArt(() -> RedPlantsArt.packetImage("red_peashooter"))  // per draw, null = game draws it
                .onPlanted(RedPlantsArt::onPlanted)   // recolours the retail track images
                .onTick(RedPlantsArt::onTick)         // the blink reanimation is per blink
                .register();
    }

    private static void registerRedGatling() {
        redGatling = PlantType.builder("red_gatling")
                .name("Red Gatling Pea")
                .tooltip("Fires a four-pea burst of burning peas.")
                .cost(275)
                .refreshTime(5000)                        // the gatling pea's own recharge
                .reanimation(ReanimationType.REANIM_GATLINGPEA)
                .subClass(PlantSubClass.SUBCLASS_SHOOTER)
                .launchRate(150)
                .burst(4)                                 // the gatling pea's four peas; without it: one
                .upgrade(true)                            // the purple (upgrade) packet background
                .upgradeFrom(SeedType.SEED_REPEATER,      // plantable on a retail ...
                        redRepeater.seedType())           // ... or a red repeater, replacing it
                .projectile(ProjectileType.PROJECTILE_FIREBALL)
                .unlockAfter(SeedType.SEED_GATLINGPEA)    // the shop purchase releases it
                .packetArt(() -> RedPlantsArt.packetImage("red_gatling"))
                .onPlanted(RedPlantsArt::onPlanted)
                .onTick(RedPlantsArt::onTick)
                .register();
    }
}
```

Once `register()` returns, the plant is a normal piece of game content: the seed packet, the almanac,
`Board#canPlantAt`, `Board#addPlant`, the seed chooser and `Plant::Fire` all treat it like a built-in.
Clients of the API can also observe it from outside via `PlantRegistry` and `CustomPlantEvents`.

## The art

Nothing is shipped as a file and nothing is hand-drawn: each icon is whatever **the game itself** draws
on its twin's seed packet, rendered into a blank packet-sized image by `GameArt.packetArt(seedType)`
(it calls `SeedPacket#seedPacketDrawSeed` with that seed's own packet placement, e.g. (5, 9) for the
peashooter and repeater and (2, 9) for the gatling pea) and then re-mapped to red by this mod. The jar
therefore carries no textures at all, and an icon cannot depict a different plant than the one on the
lawn - a hand-rendered copy of the retail art could, and one did (`../verification/VERIFICATION.md` §46).

What this mod chooses, and where it comes from:

| choice | where |
|---|---|
| the packet icons are each twin's packet art, at that seed's own placement, re-mapped | `GameArt.packetArt` + `ModImages.remapHue` |
| the board plant is the twin's retail reanimation with every art-carrying track re-mapped | `ModReanims.overrideTrackArt(reanim, mapper)` + `RedPlantsArt#redCopy` |
| the hue pivot (90 deg) and compression (0.25), shared by the three plants | `RedPlantsArt.RED_PIVOT_HUE`, `RED_HUE_COMPRESSION` |
| the packet icons reach the game as a lazy art supplier, built on the first draw that can build them | `PlantType.Builder#packetArt(Supplier<Image>)` + `RedPlantsArt#packetImage(String)` |

Which tracks are recoloured is no longer a hand-kept list: `ModReanims.overrideTrackArt(reanim,
mapper)` walks the reanimation's own definition and maps the tracks that carry an image (motion tracks
like `anim_idle` / `anim_stem` have none and are left alone), so the mod does not maintain 13/20 track
names per plant.

What the API modules do with those choices, so the mod does not have to:

* **the packet icon** - the plants' art supplier is asked by the game's own packet draw
  (`PlantType.Builder#packetArt(Supplier<Image>)`, injected into `SeedPacket::seedPacketDrawSeed`),
  once per draw: an icon is drawn 1:1 and the game's own drawing of that packet is cancelled, `null`
  means "not built yet" and the game draws the packet itself. That is what makes the icon survive
  everything the game does to its own resource table, and what makes it appear in the seed chooser -
  where no tick ever runs, so a mechanism that only retried from a tick hook would show the plain
  packet background there (`../verification/VERIFICATION.md` §53).
* **the cached plant frame** - the cursor preview and the zen garden draw
  `ReanimatorCache#mPlantImages[seedType]`, which the game builds from the reanimation and therefore
  does not know about the per-plant recolouring. `GameArt.cachedPlantFrame` +
  `GameArt.installCachedPlantFrame` put this mod's red frame there, so the plant is red under the mouse
  pointer too.
* **the head and blink reanimations** - the game creates the head layer only for its own peashooter
  seed types and the blink reanimation per blink; `ModReanims.giveSeparateHeadLayer` creates the head
  (idempotently) and this mod's tick hook re-colours the blink through the same `overrideTrackArt`.
* **image slots** - `api-resource`'s `ModImageSlot` pool (a retail `ImageID` this port never reads,
  re-installed across resource group loads) is for a mod whose art *is* a resource file that exists
  when it registers; this mod builds its icons at runtime and therefore hands them over as a supplier.

## Build

```bash
./gradlew build
```

produces `build/libs/pvz-content-mod-1.1.0.jar`, the installable mod jar (metadata and classes;
no mixin config and no art files - the art is built from the game's own at runtime). `./gradlew publishToMavenLocal` publishes
`cn.enaium.fabricpvz:pvz-content-mod:1.1.0` if another project wants to depend on it.

The loader, the Mixin runtime, ASM, the game and the API modules are `compileOnly`: a mod jar never
bundles them, they come from the installation at runtime. They are not declared here either — this
project's whole build file is the toolchain plugin plus its coordinates:

```kotlin
plugins { id("cn.enaium.fabricpvz.loom") version "1.0.5" }
group = "cn.enaium.fabricpvz"
version = "1.1.0"
```

`cn.enaium.fabricpvz.loom` puts the real Fabric Loader, Mixin, ASM, the API and the game on the
compile and test class paths, wires the end-to-end test's launcher class path, publishes the jar, and
adds `./gradlew runClient`, a development run: the game comes from the distribution, this mod's own
output (its classes and resources) *and* the API aggregate jar are on the class path, and the loader
discovers the mod there (`-Dfabric.development=true`) instead of in `run/mods/` - so a run reflects
the sources and nothing is copied around. The version the build states is served by the Gradle Plugin
Portal (`(cd ../loom && ./gradlew publishPlugins)`), so the settings file lists the portal first and
keeps `mavenLocal()` after it only as a fallback for a version the portal does not serve yet.

## Install

```
<game directory>/            <- the directory the launcher uses as -Dpvz.gameDir
├── mods/
│   └── pvz-content-mod-1.1.0.jar   # carries the API: META-INF/jars/fabric-api-1.2.2.jar
└── userdata/
```

Copy the built jar into `<gamedir>/mods` (or point Fabric Loader at another directory with
`-Dfabric.modsFolder=<dir>`) and start the game through Fabric Loader's `KnotClient` as usual. The
mod needs `pvz-portable`, `api-base`, `api-events`, `api-plant`, `api-resource` and `api-zombie` to be
present; one jar - this mod's own - provides all five API mods, because it carries the aggregate
(`fabric-api-1.2.2.jar`, whose nested jars under `META-INF/jars/` are the modules; see
`loom/README.md`, "Jar-in-jar"), and `pvz-portable` is the game itself.

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

* the three plants are registered and the game's own definition table (`Plant#getPlantDefinition`)
  reports the registered cost, recharge, launch rate, reanimation, subclass and burst, plus their names
  and tooltips (175/225/275 sun, the twin reanimations, burst 1/2/4);
* each seed is gated like its twin: the Red Peashooter behind the Snow Pea's award, the Red Repeater
  behind the adventure's Repeater, and the Red Gatling Pea behind the shop purchase
  (`mPurchases[STORE_ITEM_PLANT_GATLINGPEA]`) - `LawnApp#hasSeedType` is false for a first-time player
  and true once each gate is the player's;
* the almanac shows them on the plant grid's second page (`.almanacDescription(...)` supplies the card
  text; the portrait the card draws is a real plant, so it needs - and gets - the head reanimation the
  game creates only for its own pea shooters);
* the seed chooser offers them on a second page, in the first squares of that page, and not on the
  retail page;
* the packets are drawn where the player picks seeds - right after the app exists, **before any board
  is created or ticked** - and each shows the mod's plant: the three registered seeds' packets have
  466/507/495 red pixels where their retail twins' own packets have none, they paint the same number
  of pixels as those twins' packets (531/567/790), the icon is 50x70, its mask agrees with its twin's
  packet art pixel for pixel, and it survives both a resource group load and a board tick;
* the purple packet is the game's own answer to `Plant::IsUpgrade`: only the red gatling pea reports
  `true` (the retail gatling pea it copies does too), the peashooter and repeater twins report `false`;
* on a real headless board each plant is placeable with the registered stats, its retail art is
  recoloured (every art-carrying track of its body and head reanimations, the headleaf/barrel tracks
  included), and it fires `PROJECTILE_FIREBALL`s at a zombie in its lane;
* the volleys are the twins': the peashooter one pea, the repeater two, the gatling pea four (a volley
  counted over one launch window);
* planting an upgrade goes through the game's own path - the seed packet is picked up and dropped with
  `Board#mouseDown`, and it is the game that kills the replaced plant - with the retail pair as the
  control: the shop's gatling pea on a retail repeater reports `PLANTING_OK` and replaces it, and the
  red gatling pea does the same on a red repeater **and** on a retail one, while an empty square
  reports `PLANTING_NEEDS_UPGRADE` for both (`upgradeFrom`);
* the run ends with the game still in `GameScenes.SCENE_PLAYING` and no exception anywhere.

When this machine has the retail assets, the harness also mounts the real `main.pak` and checks it is
readable - the packet icons are rendered out of the retail art at runtime; without the assets the
packet-pixel checks are skipped and the registration, unlock and chooser checks still run.

## License

MIT (see `LICENSE`). The game port itself (`PvZ-Portable`) is LGPL-3.0-or-later with the PopCap framework licence.
