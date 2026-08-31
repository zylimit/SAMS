import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import "ol/ol.css";
import "./styles/theme.css";
import "./styles/styles.css";
import App from "./App.vue";
import { i18n } from "./i18n";

createApp(App).use(createPinia()).use(i18n).use(ElementPlus).mount("#app");
