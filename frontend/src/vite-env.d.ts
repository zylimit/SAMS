/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_CARTO_KEY?: string;
}
interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare const __APP_VERSION__: string;
declare const __BUILD_TIME__: string;
declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<object, object, unknown>;
  export default component;
}
