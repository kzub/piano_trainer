'use strict';
const assert = require('node:assert/strict');
const selection = require('../app/src/main/assets/score-selection.js');
const { pickNote, rangeEdges } = selection;
const a = { rect: { left: 100, right: 110, top: 30, bottom: 40 } };
const b = { rect: { left: 120, right: 130, top: 30, bottom: 40 } };
assert.equal(pickNote([a, b], 106, 35, 12), a);
assert.equal(pickNote([a, b], 117, 35, 12), b);
assert.equal(pickNote([a, b], 90, 35, 12), a);
assert.equal(pickNote([a, b], 20, 35, 12), null);
assert.equal(pickNote([a], 105, 80, 12), null);
const measure = { n: 1, s: 0, d: 1920 }, box = { left: 10, right: 510 };
const notes = [
    { group: { tick: 480, measure: 1 }, left: 90, right: 100 },
    { group: { tick: 480, measure: 1 }, left: 92, right: 104 }, // both staves
    { group: { tick: 960, measure: 1 }, left: 300, right: 310 }, // nonlinear engraving
];
assert.deepEqual(rangeEdges(measure, 480, 960, true, true, notes, box),
    { left: 87, right: 313, startBoundary: true, endBoundary: true });
assert.deepEqual(rangeEdges(measure, 480, 480, true, true, notes, box),
    { left: 87, right: 107, startBoundary: true, endBoundary: true });
assert.equal(rangeEdges(measure, 1920, null, true, false, notes, box), null);
assert.equal(rangeEdges(measure, null, -1, false, true, notes, box), null);
assert.deepEqual(rangeEdges(measure, 0, 1919, false, false, notes, box),
    { left: 10, right: 510, startBoundary: true, endBoundary: true });
assert.equal(rangeEdges(measure, null, 480, false, true, notes, box).left, 10);
assert.equal(rangeEdges(measure, 480, null, true, false, notes, box).right, 510);
// On a subsequent page, a selection spanning the page fills intermediate measures.
assert.deepEqual(rangeEdges({ n: 4, s: 5760, d: 1920 }, 480, 8000, true, true, [], box),
    { left: 10, right: 510, startBoundary: false, endBoundary: false });
// Geometry follows a zoom/scroll transform rather than stale SVG coordinates.
const zoomed = notes.map(n => ({ ...n, left: n.left * 2 - 50, right: n.right * 2 - 50 }));
assert.equal(rangeEdges(measure, 480, 960, true, true, zoomed, { left: -30, right: 970 }).left, 127);
console.log('Score selection: hit targets, chord bounds, exact engraving, open bounds, pages and zoom passed.');

// Exercise the installed event handlers: a swipe/pinch must never change the selection.
const handlers = {}, selected = [];
global.Element = class Element {};
global.document = {
    addEventListener: (name, callback) => { handlers[name] = callback; },
    querySelectorAll: () => [{ dataset: { id: 'chord' }, querySelector: () => null,
        getBoundingClientRect: () => ({ ...a.rect, width: 10, height: 10 }) }],
};
selection.setGroups([{ tick: 480, measure: 1, ids: ['chord'] }]);
selection.install({ noteSelected: (key, tick) => selected.push([key, tick]), measureSelected: n => selected.push(n) },
    () => ({ dataset: { n: '2' } }), 'score:0');
handlers.pointerdown({ isPrimary: true, clientX: 105, clientY: 35 });
handlers.click({ clientX: 105, clientY: 35 });
assert.deepEqual(selected, [['score:0', 480]]);
handlers.pointerdown({ isPrimary: true, clientX: 105, clientY: 35 });
handlers.pointermove({ clientX: 150, clientY: 35 });
handlers.click({ clientX: 105, clientY: 35 });
assert.equal(selected.length, 1);
handlers.pointerdown({ isPrimary: true, clientX: 105, clientY: 35 });
handlers.pointerdown({ isPrimary: false, clientX: 110, clientY: 35 });
handlers.click({ clientX: 105, clientY: 35 });
assert.equal(selected.length, 1);
handlers.pointerdown({ isPrimary: true, clientX: 400, clientY: 35 });
handlers.click({ clientX: 400, clientY: 35 });
assert.equal(selected[1], 2, 'empty space falls back to a whole measure');
delete global.document; delete global.Element;
console.log('Score selection gesture regressions passed.');
