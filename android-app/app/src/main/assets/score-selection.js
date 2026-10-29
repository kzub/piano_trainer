/* Exact score positions; client geometry is re-read after zoom and scroll. */
var PianoTrainerScoreSelection = (function () {
    'use strict';
    let byId = new Map();
    function setGroups(groups) {
        byId = new Map();
        (groups || []).forEach(group => (group.ids || []).forEach(id => {
            const matches = byId.get(id) || [];
            matches.push(group); byId.set(id, matches);
        }));
    }
    function pickNote(entries, x, y, radius) {
        let best = null, distance = Infinity;
        entries.forEach(entry => {
            const r = entry.rect;
            const dx = Math.max(r.left - x, 0, x - r.right), dy = Math.max(r.top - y, 0, y - r.bottom);
            const edge = Math.hypot(dx, dy);
            const score = edge * 4 + Math.hypot(x - (r.left + r.right) / 2, y - (r.top + r.bottom) / 2);
            if (edge <= radius && score < distance) { best = entry; distance = score; }
        });
        return best;
    }
    function entries() {
        return [...document.querySelectorAll('.Note[data-id],.note[data-id]')].flatMap(note => {
            const matches = byId.get(note.dataset.id) || [];
            if (!matches.length) return [];
            const rect = (note.querySelector('.notehead') || note).getBoundingClientRect();
            if (!rect.width || !rect.height) return [];
            // Tied glyphs resolve to the attack, not an invented new MIDI onset.
            return [{ note, rect, group: matches[0] }];
        });
    }
    function install(bridge, measureAtPoint, pageKey) {
        let down = null, moved = false, multitouch = false;
        document.addEventListener('pointerdown', event => {
            if (!event.isPrimary) { multitouch = true; return; }
            down = { x: event.clientX, y: event.clientY }; moved = false; multitouch = false;
        });
        document.addEventListener('pointermove', event => {
            if (down && Math.hypot(event.clientX - down.x, event.clientY - down.y) > 8) moved = true;
        });
        document.addEventListener('pointercancel', () => { moved = true; });
        document.addEventListener('click', event => {
            if (moved || multitouch) return;
            const hit = pickNote(entries(), event.clientX, event.clientY, 12);
            if (hit) { bridge.noteSelected(pageKey, hit.group.tick); return; }
            const direct = event.target instanceof Element ? event.target.closest('.measure[data-n]') : null;
            const measure = direct || measureAtPoint(event.clientX, event.clientY);
            const number = measure && parseInt(measure.dataset.n, 10);
            if (Number.isInteger(number)) bridge.measureSelected(number);
        });
    }
    function rangeEdges(measure, from, to, fromIsNote, toIsNote, notes, box) {
        const start = measure.s, end = start + measure.d;
        if ((to !== null && to < start) || (from !== null && from >= end)) return null;
        function noteX(tick, right) {
            const matching = notes.filter(e => e.group.tick === tick && e.group.measure === measure.n);
            if (!matching.length) return null;
            return right ? Math.max(...matching.map(e => e.right)) : Math.min(...matching.map(e => e.left));
        }
        let left = box.left, right = box.right;
        if (from !== null && from >= start) {
            const exact = fromIsNote ? noteX(from, false) : null;
            left = exact === null ? box.left + (box.right - box.left) * (from - start) / measure.d : exact - 3;
        }
        if (to !== null && to < end) {
            const exact = toIsNote ? noteX(to, true) : null;
            right = exact === null ? box.left + (box.right - box.left) * (to + 1 - start) / measure.d : exact + 3;
        }
        return { left, right, startBoundary: from !== null && from >= start && from < end,
            endBoundary: to !== null && to >= start && to < end };
    }
    function showRange(from, to, fromIsNote, toIsNote, measures) {
        document.querySelectorAll('.range-highlight').forEach(e => e.remove());
        if (from === null && to === null) return;
        const notes = entries().map(e => ({ group: e.group, left: e.rect.left, right: e.rect.right }));
        document.querySelectorAll('.measure[data-n]').forEach(m => {
            const info = measures.find(value => value.n === Number(m.dataset.n));
            if (!info) return;
            const rect = m.getBoundingClientRect(), box = m.getBBox(), parent = m.parentNode;
            const edges = rangeEdges(info, from, to, fromIsNote, toIsNote, notes, rect);
            if (!edges) return;
            const left = screenPointIn(parent, edges.left, rect.top).x, right = screenPointIn(parent, edges.right, rect.top).x;
            const shade = svgEl('rect');
            shade.setAttribute('class', 'range-highlight'); shade.setAttribute('x', left); shade.setAttribute('y', box.y);
            shade.setAttribute('width', Math.max(1, right - left)); shade.setAttribute('height', box.height);
            shade.setAttribute('fill', '#e0a800'); shade.setAttribute('opacity', '0.14');
            shade.style.pointerEvents = 'none'; parent.insertBefore(shade, m);
            for (const [active, x, direction] of [[edges.startBoundary, left, 1], [edges.endBoundary, right, -1]]) {
                if (!active) continue;
                const bracket = svgEl('path'), arm = Math.max(20, box.width * 0.012);
                bracket.setAttribute('class', 'range-highlight');
                bracket.setAttribute('d', 'M' + (x + direction * arm) + ' ' + box.y + 'H' + x + 'V' + (box.y + box.height) + 'H' + (x + direction * arm));
                bracket.setAttribute('fill', 'none'); bracket.setAttribute('stroke', '#b07800');
                bracket.setAttribute('stroke-width', '2'); bracket.setAttribute('vector-effect', 'non-scaling-stroke');
                bracket.style.pointerEvents = 'none'; parent.appendChild(bracket);
            }
        });
    }
    return { setGroups, pickNote, rangeEdges, install, showRange };
}());
if (typeof module !== 'undefined' && module.exports) module.exports = PianoTrainerScoreSelection;
