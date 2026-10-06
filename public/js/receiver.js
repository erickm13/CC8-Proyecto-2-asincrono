// Receptor confiable (espejo de ReliableReceiver.java): ACK acumulativo + SACK,
// entrega fuera de orden y deduplicación. El navegador hace el framing WebSocket.

import { parseData, encodeAck } from './wire.js';

export class Receiver {
    constructor(send, onDeliver, rwnd) {
        this.send = send;            // (ArrayBuffer) => void
        this.onDeliver = onDeliver;  // (payload Uint8Array) => void
        this.rwnd = rwnd;
        this.rcvNxt = 0;
        this.ooo = new Set();
        this.delivered = 0;
        this.duplicates = 0;
    }

    onData(buf) {
        const d = parseData(buf);
        const seq = d.seq;
        if (seq === this.rcvNxt) {
            this.deliver(d.payload);
            this.rcvNxt++;
            while (this.ooo.has(this.rcvNxt)) { this.ooo.delete(this.rcvNxt); this.rcvNxt++; }
        } else if (seq > this.rcvNxt) {
            if (!this.ooo.has(seq)) { this.ooo.add(seq); this.deliver(d.payload); }
            else this.duplicates++;
        } else {
            this.duplicates++;
        }
        this.sendAck(d.sendTs);
    }

    deliver(payload) {
        this.delivered++;
        this.onDeliver(payload);
    }

    sendAck(sendTsBytes) {
        this.send(encodeAck(this.rcvNxt, this.rwnd, sendTsBytes, this.sackBlocks()));
    }

    sackBlocks() {
        if (this.ooo.size === 0) return [];
        const arr = [...this.ooo].sort((a, b) => a - b);
        const blocks = [];
        let start = arr[0], prev = arr[0];
        for (let i = 1; i < arr.length; i++) {
            const s = arr[i];
            if (s === prev + 1) { prev = s; }
            else { blocks.push([start, prev + 1]); start = s; prev = s; }
        }
        blocks.push([start, prev + 1]);
        return blocks.length > 4 ? blocks.slice(blocks.length - 4) : blocks;
    }
}
