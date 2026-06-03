import {
  defineConfig,
  presetAttributify,
  presetIcons,
  presetTypography,
  presetUno,
  presetWebFonts,
  transformerDirectives,
  transformerVariantGroup
} from 'unocss';

export default defineConfig({
  shortcuts: {
    'panel-title':
      'pb-[5px] font-sans leading-[1.1] font-medium text-base text-[#6379bb] border-b border-b-solid border-[var(--el-border-color-light)] mb-5 mt-0'
  },
  theme: {
    colors: {
      primary: 'var(--el-color-primary)',
      primary_dark: 'var(--el-color-primary-light-5)',
      'cyber-bg': '#030712',
      'cyber-card': '#0f172a',
      'neon-blue': '#3b82f6',
      'danger': '#ef4444',
      'warning': '#eab308',
      'success': '#22c55e',
      'accent': '#8b5cf6',
    }
  },
  presets: [
    presetUno(),
    presetAttributify(),
    presetIcons(),
    presetTypography(),
    presetWebFonts({
      fonts: {}
    })
  ],
  transformers: [transformerDirectives(), transformerVariantGroup()]
});
