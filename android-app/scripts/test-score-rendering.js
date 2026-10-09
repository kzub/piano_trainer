'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const rendering = require('../app/src/main/assets/score-rendering.js');

// MuseScore assigns one segment id to a chord spanning both staves. Map the
// complete chord first, then filter to the currently practised hand.
const chord = rendering.assignGlyphPitches([
    { id: 'segment-0', y: 1567.94, staff: 'treble' },
    { id: 'segment-0', y: 2540.02, staff: 'bass' },
], { 'segment-0': [61, 77] });
assert.deepEqual(chord.map(note => [note.staff, note.pitch]), [
    ['treble', 77],
    ['bass', 61],
]);
assert.deepEqual(chord.filter(note => [61].includes(note.pitch)).map(note => note.staff), ['bass']);
assert.deepEqual(chord.filter(note => [77].includes(note.pitch)).map(note => note.staff), ['treble']);
const unison = rendering.assignGlyphPitches([
    { id: 'segment-2', y: 1, staff: 'treble' },
    { id: 'segment-2', y: 2, staff: 'bass' },
], { 'segment-2': [{ pitch: 60, hand: 'right' }, { pitch: 60, hand: 'left' }] });
assert.equal(unison.length, 2, 'shared ids preserve same-pitch glyphs on both staves');
const bassOnly = rendering.assignGlyphPitches([
    { id: 'segment-9', y: 1, staff: 'treble' },
    { id: 'segment-9', y: 2, staff: 'bass' },
], { 'segment-9': [{ pitch: 60, hand: 'left' }] });
assert.deepEqual(bassOnly.map(note => [note.staff, note.pitch]), [['bass', 60]],
    'a shared segment with only bass C4 must select the bass glyph');
assert.deepEqual(rendering.assignGlyphPitches([
    { id: 'no-lines', y: 1, location: 'top' },
    { id: 'no-lines', y: 2, location: 'bottom' },
], { 'no-lines': [{ pitch: 60, hand: 'left' }] }).map(note => note.location),
['bottom'], 'bass glyph remains the lower one when staff lines cannot be classified');
assert.deepEqual(rendering.closestExpectedEntries(unison, 61).map(note => note.staff),
    ['treble', 'bass'], 'a neighbouring wrong note is shown on both engraved C4 staves');
assert.deepEqual(rendering.closestExpectedEntries(chord, 62).map(note => note.staff),
    ['bass'], 'a bass C#4 stays beside its bass reference');
assert.deepEqual(rendering.closestExpectedEntries(chord, 76).map(note => note.staff),
    ['treble'], 'a treble E4 stays beside its treble reference');

// Every letter and alteration has a visible test note on each clef. The two
// octaves are deliberately separate MIDI pitches despite sharing a name.
const fixture = fs.readFileSync(path.join(__dirname, '../../sample/note-display-test.musicxml'), 'utf8');
const letters = 'CDEFGAB';
const semitones = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
const signatureForVariant = {
    C: [-6, 0], D: [-5, 2], E: [-2, 6], F: [-7, 1],
    G: [-6, 3], A: [-4, 5], B: [-1, 7],
};
const cases = [...fixture.matchAll(/<measure number="(\d+)">([\s\S]*?)<\/measure>/g)];
assert.equal(cases.length, 23);
for (const [letterIndex, letter] of [...letters].entries()) {
    for (const [variantIndex, alteration] of [0, -1, 1].entries()) {
        const [_, number, measure] = cases[letterIndex * 3 + variantIndex];
        const notes = [...measure.matchAll(/<note>([\s\S]*?)<\/note>/g)].map(match => match[1]);
        assert.equal(notes.length, 2, `${letter} measure ${number} has both staves`);
        for (const [staffIndex, octave] of [4, 3].entries()) {
            const note = notes[staffIndex];
            const pitch = (octave + 1) * 12 + semitones[letter] + alteration;
            assert.match(note, new RegExp(`<step>${letter}</step>`));
            assert.match(note, new RegExp(`<octave>${octave}</octave>`));
            assert.match(note, new RegExp(`<staff>${staffIndex + 1}</staff>`));
            if (alteration) {
                assert.match(note, new RegExp(`<alter>${alteration}</alter>`));
                assert.match(note, new RegExp(`<accidental>${alteration < 0 ? 'flat' : 'sharp'}</accidental>`));
            }
            const fifths = alteration === 0 ? 0 : signatureForVariant[letter][alteration < 0 ? 0 : 1];
            const spelling = rendering.spellPitch(pitch, fifths);
            assert.deepEqual([spelling.letter, spelling.alteration, spelling.octave],
                [letter, alteration, octave], `${letter}${alteration} ${octave} in staff ${staffIndex + 1}`);
            assert.equal(rendering.staffPositionForPitch(pitch, fifths), octave * 7 + letterIndex);
        }
        const bassPitch = 48 + semitones[letter] + alteration;
        const treblePitch = bassPitch + 12;
        const assigned = rendering.assignGlyphPitches([
            { id: `measure-${number}`, y: 100, staff: 'treble' },
            { id: `measure-${number}`, y: 200, staff: 'bass' },
        ], { [`measure-${number}`]: [
            { pitch: bassPitch, hand: 'left' }, { pitch: treblePitch, hand: 'right' },
        ] });
        assert.deepEqual(assigned.map(note => [note.staff, note.pitch]),
            [['treble', treblePitch], ['bass', bassPitch]]);
    }
}
assert.match(cases[21][2], /<step>C<\/step>[\s\S]*?<octave>4<\/octave>[\s\S]*?<staff>2<\/staff>/);
assert.match(cases[22][2], /<step>C<\/step>[\s\S]*?<octave>4<\/octave>[\s\S]*?<staff>2<\/staff>/);
const bassMiddleC = rendering.assignGlyphPitches([
    { id: 'bass-middle-c', y: 100, staff: 'treble' },
    { id: 'bass-middle-c', y: 200, staff: 'bass' },
], { 'bass-middle-c': [{ pitch: 60, hand: 'left' }, { pitch: 72, hand: 'right' }] });
assert.deepEqual(bassMiddleC.map(note => [note.staff, note.pitch]),
    [['treble', 72], ['bass', 60]], 'bass C4 must not be confused with treble C5');
assert.deepEqual(rendering.closestExpectedEntries(bassMiddleC, 61).map(note => note.staff),
    ['bass'], 'a C#4 press uses the real bass C4 reference');
assert.equal(rendering.staffPositionForPitch(60, 0), 28,
    'C4 has the same absolute staff position whether engraved in bass or treble');
const ledgerChord = rendering.assignGlyphPitches([
    { id: 'segment-48', y: 148, staff: 'treble' },
    { id: 'segment-48', y: 261, staff: 'bass' },
    { id: 'segment-48', y: 291, staff: 'bass' }, // D2 below the measure rectangle
], { 'segment-48': [
    { pitch: 71, hand: 'right' },
    { pitch: 50, hand: 'left' },
    { pitch: 38, hand: 'left' },
] });
assert.deepEqual(ledgerChord.map(note => [note.pitch, note.y]),
    [[71, 148], [50, 261], [38, 291]]);

// In Db major (five flats), show a wrong C4 on the bass staff, keep D natural
// on the D line with a natural sign, and spell the next semitone as E-flat.
assert.deepEqual(rendering.spellPitch(60, -5), {
    letter: 'C', letterIndex: 0, alteration: 0, octave: 4, score: 0,
});
assert.equal(rendering.staffPositionForPitch(60, -5), 28);
assert.equal(rendering.staffPositionForPitch(61, -5), 29);
assert.equal(rendering.staffPositionForPitch(62, -5), 29);
assert.equal(rendering.staffPositionForPitch(63, -5), 30);
assert.equal(rendering.accidentalForPitch(60, -5), null);
assert.equal(rendering.accidentalForPitch(61, -5), null);
assert.equal(rendering.accidentalForPitch(62, -5), '♮');
assert.equal(rendering.accidentalForPitch(63, -5), null);
assert.deepEqual(rendering.spellPitch(59, -6), {
    letter: 'C', letterIndex: 0, alteration: -1, octave: 4, score: 0,
});
assert.equal(rendering.staffPositionForPitch(59, -6), 28);
assert.deepEqual(rendering.spellPitch(60, 7), {
    letter: 'B', letterIndex: 6, alteration: 1, octave: 3, score: 0,
});
assert.equal(rendering.staffPositionForPitch(60, 7), 27);

// Resolve the staff from the expected glyph's physical location, regardless
// of the wrong pitch's octave or a middle-C threshold.
assert.equal(rendering.closestStaffToReference([
    { clef: 'treble', center: 1696.82 },
    { clef: 'bass', center: 2765.56 },
], { pitch: 61, y: 2540.02 }).clef, 'bass');
assert.equal(rendering.closestExpectedEntry(chord, 60).staff, 'bass');

// The notehead uses a .779429 matrix scale. A delta in root SVG coordinates
// must be added after that matrix, so the 12-unit correction stays 12 units.
const translated = rendering.matrixAtOffset(
    { a: 0.779429, b: 0, c: 0, d: 0.779429, e: 1426.89, f: 2540.02 },
    12,
    32.22,
);
assert.deepEqual(translated, {
    a: 0.779429, b: 0, c: 0, d: 0.779429, e: 1438.89, f: 2572.24,
});
assert.equal(rendering.matrixText(translated), 'matrix(0.779429,0,0,0.779429,1438.89,2572.24)');
const rootScale = 1920 / 9924.1;
const rootScreenMatrix = { a: rootScale, b: 0, c: 0, d: rootScale, e: 100, f: 30 };
const noteScreenMatrix = {
    a: rootScale * 0.779429,
    b: 0,
    c: 0,
    d: rootScale * 0.779429,
    e: rootScale * 1426.89 + 100,
    f: rootScale * 2540.02 + 30,
};
const noteInRoot = rendering.matrixRelativeToRoot(rootScreenMatrix, noteScreenMatrix);
for (const field of ['a', 'b', 'c', 'd', 'e', 'f']) {
    assert.ok(Math.abs(noteInRoot[field] - { a: 0.779429, b: 0, c: 0, d: 0.779429, e: 1426.89, f: 2540.02 }[field]) < 1e-8);
}

// Compile the actual WebView script after substituting its shared helper asset.
const activity = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/konstantin/pianotrainer/MainActivity.kt'), 'utf8');
const exactSelection = activity.slice(activity.indexOf('function visualSelection('), activity.indexOf('const entries=noteEntries', activity.indexOf('function visualSelection(')));
assert.doesNotMatch(exactSelection, /pointInsideMeasures/,
    'exact score IDs must retain ledger notes outside the measure rectangle');
const scriptMarker = '<script>$scoreRenderingHelpers';
const scriptStart = activity.indexOf(scriptMarker);
assert.notEqual(scriptStart, -1, 'MainActivity must inject the shared renderer helper asset');
const bodyStart = scriptStart + scriptMarker.length;
const scriptEnd = activity.indexOf('</script>', bodyStart);
assert.notEqual(scriptEnd, -1, 'MainActivity WebView script must have a closing tag');
const inlineScript = activity.slice(bodyStart, scriptEnd);
const rendererAsset = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/score-rendering.js'), 'utf8');
assert.doesNotThrow(() => new vm.Script(rendererAsset + inlineScript), 'WebView renderer JavaScript must parse');

console.log('Score rendering regressions passed.');
