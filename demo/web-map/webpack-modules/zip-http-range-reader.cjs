const {Reader} = require("@zip.js/zip.js");

module.exports = class DelegatingRangeReader extends Reader {
    constructor(size, readUint8Array, createReadable) {
        super();
        this.size = size;
        this.delegatedReadUint8Array = readUint8Array;
        this.delegatedCreateReadable = createReadable;
    }

    readUint8Array(index, length) {
        return this.delegatedReadUint8Array(index, length);
    }

    createReadable(options) {
        return this.delegatedCreateReadable(options);
    }
};
