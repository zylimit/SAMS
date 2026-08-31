import { defineConfig, loadEnv, type Plugin } from "vite";
import vue from "@vitejs/plugin-vue";
import { spawn } from "node:child_process";

function curlGet(url: string): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const child = spawn("curl", ["-sS", "-L", "--max-time", "20", url], { env: process.env });
    const chunks: Buffer[] = [];
    const err: Buffer[] = [];
    child.stdout.on("data", (c) => chunks.push(c));
    child.stderr.on("data", (c) => err.push(c));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code !== 0) reject(new Error(Buffer.concat(err).toString() || `curl ${code}`));
      else resolve(Buffer.concat(chunks));
    });
  });
}

async function getTile(url: string): Promise<Buffer> {
  if (process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY) {
    return curlGet(url);
  }
  const r = await fetch(url);
  if (!r.ok) throw new Error(String(r.status));
  return Buffer.from(await r.arrayBuffer());
}

/** Browser → Vite → Carto/Esri/Google. Avoids corporate MITM cert failures on tile hosts. */
function tileProxy(cartoKey: string): Plugin {
  const routes = [
    { prefix: "/carto-tiles", origin: "https://basemaps.cartocdn.com", addKey: true },
    { prefix: "/esri-tiles", origin: "https://server.arcgisonline.com" },
    { prefix: "/gtiles", origin: "https://mt0.google.com" },
  ] as const;
  return {
    name: "basemap-tile-proxy",
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const raw = req.url ?? "";
        const pathOnly = raw.split("?")[0];
        const route = routes.find((r) => pathOnly === r.prefix || pathOnly.startsWith(`${r.prefix}/`));
        if (!route || req.method !== "GET") return next();
        let destPath = raw.slice(route.prefix.length) || "/";
        if (!destPath.startsWith("/")) destPath = `/${destPath}`;
        if (route.addKey && cartoKey && !destPath.includes("key=")) {
          destPath += `${destPath.includes("?") ? "&" : "?"}key=${encodeURIComponent(cartoKey)}`;
        }
        try {
          const body = await getTile(route.origin + destPath);
          const png = body[0] === 0x89 && body[1] === 0x50;
          const jpg = body[0] === 0xff && body[1] === 0xd8;
          res.statusCode = 200;
          res.setHeader("Content-Type", png ? "image/png" : jpg ? "image/jpeg" : "application/octet-stream");
          res.setHeader("Cache-Control", "public, max-age=86400");
          res.end(body);
        } catch {
          res.statusCode = 502;
          res.end("tile proxy failed");
        }
      });
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [vue(), tileProxy((env.VITE_CARTO_KEY ?? "").trim())],
    define: {
      __APP_VERSION__: JSON.stringify(process.env.npm_package_version || "1.0.0"),
      __BUILD_TIME__: JSON.stringify(new Date().toISOString().slice(0, 16).replace("T", " ")),
    },
    server: {
      host: "0.0.0.0",
      port: 5174,
      strictPort: true,
      proxy: {
        "/api": "http://127.0.0.1:8080",
        "/health": "http://127.0.0.1:8080",
      },
    },
  };
});
