const path = require("path");
const zipPackageRoot = path.dirname(require.resolve("@zip.js/zip.js/package.json"));
const zipBridgeRoot = path.resolve(__dirname, "../../../../demo/web-map/webpack-modules");

config.resolve.alias = Object.assign({}, config.resolve.alias, {
    "@zip.js/zip.js$": path.join(zipPackageRoot, "index.min.js"),
    "minecraft-web-map-zip-http-range-reader$": path.join(zipBridgeRoot, "zip-http-range-reader.cjs"),
    "minecraft-web-map-zip-reader$": path.join(zipBridgeRoot, "zip-reader.cjs"),
    "minecraft-web-map-zip-blob-writer$": path.join(zipBridgeRoot, "zip-blob-writer.cjs"),
});
