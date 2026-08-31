<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from "vue";
import Map from "ol/Map";
import View from "ol/View";
import TileLayer from "ol/layer/Tile";
import VectorLayer from "ol/layer/Vector";
import VectorSource from "ol/source/Vector";
import XYZ from "ol/source/XYZ";
import GeoJSON from "ol/format/GeoJSON";
import { fromLonLat, toLonLat } from "ol/proj";
import { Circle as CircleStyle, Fill, Stroke, Style, RegularShape, Text } from "ol/style";
import { defaults as defaultControls, ScaleLine, Attribution } from "ol/control";
import Draw, { createBox } from "ol/interaction/Draw";
import { fromCircle } from "ol/geom/Polygon";
import Feature from "ol/Feature";
import { Circle as CircleGeom, Point } from "ol/geom";
import type { Geometry } from "ol/geom";
import type { FeatureLike } from "ol/Feature";
import type { FeatureCollection } from "../api";
import { useI18n } from "vue-i18n";
import { useAppStore } from "../stores/app";
import {
  hexAlpha, LAYER_COLOR, lessorLineColor, metersToMapUnits, operatorColor,
  OPERATOR_LETTER, siteShape, siteStatusColor, STATUS_COLOR,
  type ShapeKind,
} from "../ol/utils";
import { filterByScope, scopeCtx } from "../scopes";

type BasemapKey = "positron" | "osm" | "esri" | "google";

const CARTO_KEY = (import.meta.env.VITE_CARTO_KEY ?? "").trim();
const cartoQ = CARTO_KEY ? `?key=${encodeURIComponent(CARTO_KEY)}` : "";

const LETTER_MAX_RES = 20;
const RING_MAX_RES = 80;
const POLY_LABEL_MAX_RES = 300;
const CHUNK = 2000;

function cssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

const COLOR = { siteStroke: "#fff", selected: "#3b82f6" };
function refreshChrome() {
  COLOR.siteStroke = cssVar("--feat-site-stroke") || "#fff";
  COLOR.selected = cssVar("--feat-selected-stroke") || "#3b82f6";
}

const SHAPE_CFG: Record<Exclude<ShapeKind, "circle">, { points: number; angle: number; radiusFactor: number }> = {
  triangle: { points: 3, angle: 0, radiusFactor: 1.3 },
  square: { points: 4, angle: Math.PI / 4, radiusFactor: 1.1 },
  diamond: { points: 4, angle: 0, radiusFactor: 1.25 },
};

const props = defineProps<{ npRadius: number; showLabels: boolean; showLetters: boolean }>();
const el = ref<HTMLDivElement | null>(null);
const store = useAppStore();
const { t } = useI18n();
const basemap = ref<BasemapKey>("positron");
const layers: Partial<Record<BasemapKey, TileLayer>> = {};
const fmt = new GeoJSON();
const readOpts = { dataProjection: "EPSG:4326", featureProjection: "EPSG:3857" } as const;

let map: Map | null = null;
let draw: Draw | null = null;
let resizeObs: ResizeObserver | null = null;
let loadGen = 0;
const siteSrc = new VectorSource();
const roadSrc = new VectorSource();
const lessorSrc = new VectorSource();
const areaSrc = new VectorSource();
const selSrc = new VectorSource();

let siteLayer: VectorLayer<VectorSource> | null = null;
let roadLayer: VectorLayer<VectorSource> | null = null;
let lessorLayer: VectorLayer<VectorSource> | null = null;
let areaLayer: VectorLayer<VectorSource> | null = null;

const hiddenRef = { current: store.hiddenIds };
const npRef = { current: props.npRadius };
const labelsRef = { current: props.showLabels };
const lettersRef = { current: props.showLetters };
const selectedRef = { current: "" };
const styleCache = new Map<string, Style[]>();

function isSelected(f: FeatureLike): boolean {
  return selectedRef.current !== "" && String(f.getId()) === selectedRef.current;
}

function frame(): Promise<void> {
  return new Promise((r) => requestAnimationFrame(() => r()));
}

function siteStyle(f: FeatureLike, resolution: number) {
  if (hiddenRef.current.has(String(f.getId()))) return undefined;
  const selected = isSelected(f);
  const type = String(f.get("type") ?? "");
  const status = String(f.get("site_status") ?? "");
  const cat = String(f.get("category") ?? "");
  const op = String(f.get("operator") ?? "");
  const sh = siteShape(type);
  const showLetter = lettersRef.current && !!OPERATOR_LETTER[op] && resolution < LETTER_MAX_RES;
  const showRing = !!sh.ring && resolution < RING_MAX_RES;
  const key = `${type}|${status}|${cat}|${op}|${showLetter ? 1 : 0}|${showRing ? npRef.current : 0}|${selected ? 1 : 0}`;
  const hit = styleCache.get(key);
  if (hit) return hit;

  const color = cat === "存量" ? LAYER_COLOR["存量"] : cat === "规划" ? LAYER_COLOR["规划"] : siteStatusColor(status);
  const radius = selected ? 9 : 6;
  const fill = cat === "规划"
    ? new Fill({ color: hexAlpha(LAYER_COLOR["规划"], 0.55) })
    : sh.filled ? new Fill({ color }) : undefined;
  const stroke = new Stroke({
    color: selected ? COLOR.selected
      : cat === "规划" ? LAYER_COLOR["规划"]
      : sh.filled ? COLOR.siteStroke
      : color,
    width: selected ? 3 : (sh.filled ? 1.5 : 2),
  });
  const image = sh.shape === "circle"
    ? new CircleStyle({ radius, fill, stroke })
    : new RegularShape({
        points: SHAPE_CFG[sh.shape].points,
        radius: radius * SHAPE_CFG[sh.shape].radiusFactor,
        angle: SHAPE_CFG[sh.shape].angle,
        fill,
        stroke,
      });
  const styles: Style[] = [new Style({ image })];
  if (showRing) {
    styles.push(new Style({
      geometry: (feat) => {
        const g = (feat as Feature).getGeometry();
        if (!(g instanceof Point)) return undefined;
        const coord = g.getCoordinates();
        const lat = toLonLat(coord)[1];
        return new CircleGeom(coord, metersToMapUnits(npRef.current, lat));
      },
      stroke: new Stroke({ color, width: 1, lineDash: [4, 3] }),
      fill: new Fill({ color: hexAlpha(color, 0.08) }),
    }));
  }
  if (showLetter) {
    const letter = OPERATOR_LETTER[op];
    const badgeR = selected ? 7 : 6;
    const badgeX = radius + badgeR + 1;
    styles.push(new Style({
      image: new CircleStyle({
        radius: badgeR,
        fill: new Fill({ color: operatorColor(op) }),
        stroke: new Stroke({ color: "#0b0f14", width: 1 }),
        displacement: [badgeX, 0],
      }),
    }));
    styles.push(new Style({
      text: new Text({
        text: letter,
        font: `700 ${selected ? 10 : 9}px sans-serif`,
        fill: new Fill({ color: "#fff" }),
        offsetX: badgeX,
        textAlign: "center",
        textBaseline: "middle",
      }),
    }));
  }
  styleCache.set(key, styles);
  return styles;
}

async function loadInto(src: VectorSource, fc: FeatureCollection, acc: { done: number }, total: number, gen: number) {
  src.clear();
  const list = fc.features;
  if (!list.length) return;
  for (let i = 0; i < list.length; i += CHUNK) {
    if (gen !== loadGen) return;
    src.addFeatures(fmt.readFeatures(
      { type: "FeatureCollection", features: list.slice(i, i + CHUNK) },
      readOpts,
    ));
    acc.done += Math.min(CHUNK, list.length - i);
    store.setLoadProgress(acc.done, total);
    await frame();
  }
}

async function refreshLayers() {
  const gen = ++loadGen;
  const ctx = scopeCtx(store.currentUser);
  const sites = filterByScope(ctx, "site", store.sites);
  const roads = filterByScope(ctx, "road", store.roads);
  const lessors = filterByScope(ctx, "lessor", store.lessors);
  const areas = filterByScope(ctx, "area", store.areas);
  const total = sites.features.length + roads.features.length + lessors.features.length + areas.features.length;
  if (total > 0) store.phase = "loading";
  store.setLoadProgress(0, Math.max(total, 1));
  const acc = { done: 0 };
  await loadInto(areaSrc, areas, acc, total, gen);
  await loadInto(lessorSrc, lessors, acc, total, gen);
  await loadInto(roadSrc, roads, acc, total, gen);
  await loadInto(siteSrc, sites, acc, total, gen);
  if (gen !== loadGen) return;
  const ext = siteSrc.getExtent();
  if (map && ext && ext.every((v) => Number.isFinite(v))) {
    map.getView().fit(ext, { padding: [40, 40, 40, 40], maxZoom: 15, duration: 400 });
  }
  store.finishLoading();
}

function restyle() {
  siteLayer?.changed();
  roadLayer?.changed();
  lessorLayer?.changed();
  areaLayer?.changed();
}

/** Search / 列表飞入：点只平移，已经放大过就不再缩小。 */
function flyToGeometry(geom: Geometry) {
  if (!map) return;
  const view = map.getView();
  const ext = geom.getExtent();
  if (!ext || !ext.every((v) => Number.isFinite(v))) {
    view.cancelAnimations();
    return;
  }
  view.cancelAnimations();
  const current = view.getZoom() ?? 0;
  if (geom.getType() === "Point") {
    view.animate({
      center: [(ext[0] + ext[2]) / 2, (ext[1] + ext[3]) / 2],
      zoom: Math.max(current, 17),
      duration: 600,
    });
    return;
  }
  view.fit(ext, { padding: [80, 80, 80, 80], maxZoom: Math.max(current, 18), duration: 600 });
}

function applyBasemap(key: BasemapKey) {
  basemap.value = key;
  (Object.keys(layers) as BasemapKey[]).forEach((k) => layers[k]?.setVisible(k === key));
}

onMounted(() => {
  if (!el.value) return;
  refreshChrome();
  selectedRef.current = store.selected?.id != null ? String(store.selected.id) : "";
  // Dev: tiles via Vite (corporate proxy / MITM cert). Prod: hit CDN, key from env.
  const viaDev = import.meta.env.DEV;
  layers.positron = new TileLayer({
    source: new XYZ({
      url: viaDev
        ? "/carto-tiles/light_all/{z}/{x}/{y}.png"
        : cartoQ
          ? `https://{a-d}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png${cartoQ}`
          : "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}",
      crossOrigin: viaDev ? undefined : "anonymous",
      maxZoom: 19,
      attributions: viaDev || cartoQ ? "© OpenStreetMap · © CARTO" : "© Esri",
    }),
    visible: true,
  });
  layers.osm = new TileLayer({
    source: new XYZ({
      url: viaDev
        ? "/carto-tiles/rastertiles/voyager/{z}/{x}/{y}.png"
        : cartoQ
          ? `https://{a-d}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png${cartoQ}`
          : "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}",
      crossOrigin: viaDev ? undefined : "anonymous",
      maxZoom: 19,
      attributions: viaDev || cartoQ ? "© OpenStreetMap · © CARTO" : "© Esri",
    }),
    visible: false,
  });
  layers.esri = new TileLayer({
    source: new XYZ({
      url: viaDev
        ? "/esri-tiles/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        : "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
      crossOrigin: viaDev ? undefined : "anonymous",
      maxZoom: 19,
      attributions: "© Esri",
    }),
    visible: false,
  });
  layers.google = new TileLayer({
    source: new XYZ({
      url: viaDev
        ? "/gtiles/vt/lyrs=s&x={x}&y={y}&z={z}"
        : "https://mt{0-3}.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
      crossOrigin: viaDev ? undefined : "anonymous",
      maxZoom: 20,
      attributions: "© Google",
    }),
    visible: false,
  });

  areaLayer = new VectorLayer({
    source: areaSrc,
    declutter: true,
    updateWhileAnimating: false,
    updateWhileInteracting: false,
    style: (f, res) => {
      if (hiddenRef.current.has(String(f.getId()))) return undefined;
      const selected = isSelected(f);
      const color = operatorColor(f.get("operator"));
      return new Style({
        fill: new Fill({ color: hexAlpha(color, 0.35) }),
        stroke: new Stroke({ color: selected ? COLOR.selected : color, width: selected ? 3 : 1 }),
        text: labelsRef.current && res < POLY_LABEL_MAX_RES
          ? new Text({ text: String(f.get("name") ?? ""), fill: new Fill({ color: "#111" }), font: "11px sans-serif" })
          : undefined,
      });
    },
  });
  lessorLayer = new VectorLayer({
    source: lessorSrc,
    declutter: true,
    updateWhileAnimating: false,
    updateWhileInteracting: false,
    style: (f, res) => {
      if (hiddenRef.current.has(String(f.getId()))) return undefined;
      const selected = isSelected(f);
      const line = lessorLineColor(f.get("relationship"));
      return new Style({
        fill: new Fill({ color: hexAlpha(line, 0.33) }),
        stroke: new Stroke({ color: selected ? COLOR.selected : line, width: selected ? 4 : 2 }),
        text: labelsRef.current && res < POLY_LABEL_MAX_RES
          ? new Text({ text: String(f.get("lessor_name") ?? ""), font: "11px sans-serif" })
          : undefined,
      });
    },
  });
  roadLayer = new VectorLayer({
    source: roadSrc,
    updateWhileAnimating: false,
    updateWhileInteracting: false,
    style: (f) => {
      if (hiddenRef.current.has(String(f.getId()))) return undefined;
      const selected = isSelected(f);
      return new Style({
        stroke: new Stroke({
          color: selected ? COLOR.selected : STATUS_COLOR.road,
          width: selected ? 5 : 3,
        }),
      });
    },
  });
  siteLayer = new VectorLayer({
    source: siteSrc,
    updateWhileAnimating: false,
    updateWhileInteracting: false,
    renderBuffer: 48,
    style: (f, res) => siteStyle(f, res),
  });

  map = new Map({
    target: el.value,
    layers: [
      layers.positron!, layers.osm!, layers.esri!, layers.google!,
      areaLayer, lessorLayer, roadLayer, siteLayer,
      new VectorLayer({
        source: selSrc,
        style: new Style({
          stroke: new Stroke({ color: "#10b981", width: 2 }),
          fill: new Fill({ color: "rgba(16,185,129,0.1)" }),
        }),
      }),
    ],
    view: new View({ center: fromLonLat([121.0, 14.6]), zoom: 6 }),
    controls: defaultControls({ attribution: false }).extend([new ScaleLine(), new Attribution({ collapsible: true })]),
  });
  map.getViewport().setAttribute("tabindex", "-1");
  resizeObs = new ResizeObserver(() => { map?.updateSize(); });
  resizeObs.observe(el.value);
  map.on("singleclick", (evt) => {
    if (draw) return;
    const hit = map!.forEachFeatureAtPixel(evt.pixel, (f) => f, {
      hitTolerance: 4,
      layerFilter: (l) => l.getSource() !== selSrc,
    });
    if (!hit) { store.selected = null; return; }
    const found = store.featureById.get(String(hit.getId()));
    if (found) store.selected = found;
  });
});

watch(() => [store.sites, store.roads, store.lessors, store.areas], () => { void refreshLayers(); });
watch(() => store.hiddenIds, (ids) => { hiddenRef.current = ids; restyle(); });
watch(() => props.npRadius, (n) => { npRef.current = n; styleCache.clear(); siteLayer?.changed(); });
watch(() => props.showLabels, (v) => { labelsRef.current = v; areaLayer?.changed(); lessorLayer?.changed(); });
watch(() => props.showLetters, (v) => { lettersRef.current = v; styleCache.clear(); siteLayer?.changed(); });
watch(() => store.selected?.id, (id) => {
  selectedRef.current = id != null ? String(id) : "";
  restyle();
});
watch(() => store.flyTo, (f) => {
  if (!f || !map) return;
  const existing = siteSrc.getFeatureById(String(f.id))
    ?? roadSrc.getFeatureById(String(f.id))
    ?? lessorSrc.getFeatureById(String(f.id))
    ?? areaSrc.getFeatureById(String(f.id));
  const g = existing?.getGeometry();
  if (g) { flyToGeometry(g); return; }
  if (!f.geometry) return;
  const geom = fmt.readFeature(f, readOpts).getGeometry();
  if (geom) flyToGeometry(geom);
});

watch(() => store.drawMode, (mode) => {
  if (!map) return;
  if (draw) { map.removeInteraction(draw); draw = null; }
  if (!mode) return;
  const type = mode === "circle" ? "Circle" : mode === "rect" ? "Circle" : "Polygon";
  draw = new Draw({
    source: selSrc,
    type: type as "Circle" | "Polygon",
    geometryFunction: mode === "rect" ? createBox() : undefined,
  });
  draw.on("drawend", (e) => {
    selSrc.clear();
    let geom = e.feature.getGeometry();
    if (geom instanceof CircleGeom) geom = fromCircle(geom, 64);
    e.feature.setGeometry(geom);
    selSrc.addFeature(e.feature);
    const gj = JSON.parse(fmt.writeGeometry(geom!, { featureProjection: "EPSG:3857", dataProjection: "EPSG:4326" }));
    store.selection = gj;
    store.drawMode = null;
    store.log("info", "Selection drawn");
  });
  map.addInteraction(draw);
});

onUnmounted(() => {
  loadGen++;
  resizeObs?.disconnect();
  resizeObs = null;
  map?.setTarget(undefined);
});
</script>

<template>
  <div class="map">
    <div ref="el" class="ol-map" />
    <div class="map-ctrls">
      <div class="basemap-switch">
        <button type="button" class="map-ctrl-btn" :class="{ active: basemap === 'positron' }" @click="applyBasemap('positron')">Carto</button>
        <button type="button" class="map-ctrl-btn" :class="{ active: basemap === 'osm' }" @click="applyBasemap('osm')">OSM</button>
        <button type="button" class="map-ctrl-btn" :class="{ active: basemap === 'esri' }" @click="applyBasemap('esri')">{{ t("map.basemap.esri") }}</button>
        <button type="button" class="map-ctrl-btn" :class="{ active: basemap === 'google' }" @click="applyBasemap('google')">{{ t("map.basemap.google") }}</button>
      </div>
    </div>
  </div>
</template>
