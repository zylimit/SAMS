import { createI18n } from "vue-i18n";
import { messages, type Lang } from "./messages";

const KEY = "sams.lang";

export function readLang(): Lang {
  try {
    const v = localStorage.getItem(KEY);
    if (v === "zh" || v === "en") return v;
  } catch { /* ignore */ }
  return "en";
}

export const i18n = createI18n({
  legacy: false,
  locale: readLang(),
  fallbackLocale: "en",
  messages,
});

export function setLang(lang: Lang) {
  i18n.global.locale.value = lang;
  try { localStorage.setItem(KEY, lang); } catch { /* ignore */ }
  document.documentElement.lang = lang;
  document.documentElement.setAttribute("data-lang", lang);
}

export function t(key: string, vars?: Record<string, string | number>) {
  return i18n.global.t(key, vars ?? {});
}
