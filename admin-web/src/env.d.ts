/// <reference types="vite/client" />

declare module 'nprogress' {
  interface NProgressOptions {
    minimum?: number
    easing?: string
    speed?: number
    trickle?: boolean
    trickleSpeed?: number
    showSpinner?: boolean
    parent?: string
    template?: string
  }

  interface NProgress {
    configure(options: Partial<NProgressOptions>): NProgress
    start(): NProgress
    done(): NProgress
    set(n: number): NProgress
    inc(amount?: number): NProgress
    remove(): void
  }

  const nprogress: NProgress
  export default nprogress
}
