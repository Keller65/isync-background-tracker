/**
 * Gate de runtime: nada de la API pública funciona si la app no está envuelta
 * en <IsyncLocationProvider>. El Provider entra al montar y sale al desmontar
 * (refcount: providers anidados / StrictMode no rompen el balance).
 */
let refCount = 0;

export const providerGate = {
  isActive(): boolean {
    return refCount > 0;
  },

  enter(): void {
    refCount += 1;
  },

  exit(): void {
    if (refCount > 0) refCount -= 1;
  },
};

/** Verificación por método: mensaje en el idioma de la guía de integración. */
export function requireProviderActive(api: string): void {
  if (!providerGate.isActive()) {
    throw new Error(
      `isync-background-tracker: ${api} requiere que la app esté envuelta en ` +
        `<IsyncLocationProvider>. Envuelve el árbol (o la raíz) de tu app en ` +
        `<IsyncLocationProvider> y vuelve a intentarlo.`,
    );
  }
}