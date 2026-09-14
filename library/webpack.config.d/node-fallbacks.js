// okio's Node file system is reached through a static require('fs'), which webpack cannot resolve
// for a browser bundle. Stub the Node built-ins so the bundle builds; a browser has no file system,
// and reaching for one there fails at runtime, which is the documented behaviour.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
  fs: false,
  path: false,
  os: false,
  crypto: false,
});
