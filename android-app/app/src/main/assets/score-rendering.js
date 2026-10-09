/* Pure score-rendering helpers shared by the Android WebView and Node tests. */
var PianoTrainerScoreRendering = (function () {
    'use strict';

    const LETTERS = ['C', 'D', 'E', 'F', 'G', 'A', 'B'];
    const LETTER_INDEX = { C: 0, D: 1, E: 2, F: 3, G: 4, A: 5, B: 6 };
    const NATURAL_PITCH_CLASSES = { C: 0, D: 2, E: 4, F: 5, G: 7, A: 9, B: 11 };
    const SHARP_ORDER = ['F', 'C', 'G', 'D', 'A', 'E', 'B'];
    const FLAT_ORDER = ['B', 'E', 'A', 'D', 'G', 'C', 'F'];

    function keyAlterations(fifths) {
        const alterations = { C: 0, D: 0, E: 0, F: 0, G: 0, A: 0, B: 0 };
        const count = Math.max(-7, Math.min(7, Number(fifths) || 0));
        const order = count < 0 ? FLAT_ORDER : SHARP_ORDER;
        const alteration = count < 0 ? -1 : 1;
        order.slice(0, Math.abs(count)).forEach(letter => { alterations[letter] = alteration; });
        return alterations;
    }

    function mod(value, divisor) {
        return ((value % divisor) + divisor) % divisor;
    }

    function spellPitch(pitch, fifths) {
        const midi = Number(pitch);
        if (!Number.isFinite(midi)) return null;
        const pitchClass = mod(midi, 12);
        const signature = keyAlterations(fifths);
        const flatKey = Number(fifths) < 0;
        const candidates = [];
        LETTERS.forEach((letter, letterIndex) => {
            [-1, 0, 1].forEach(alteration => {
                if (mod(NATURAL_PITCH_CLASSES[letter] + alteration, 12) !== pitchClass) return;
                const signatureAlteration = signature[letter];
                const signatureCost = alteration === signatureAlteration ? 0 : alteration === 0 ? 1 : 2;
                const directionCost = alteration === 0 ? 0 : (alteration < 0) === flatKey ? 0 : 0.25;
                candidates.push({
                    letter,
                    letterIndex,
                    alteration,
                    octave: (midi - NATURAL_PITCH_CLASSES[letter] - alteration) / 12 - 1,
                    score: signatureCost + directionCost,
                });
            });
        });
        candidates.sort((left, right) => left.score - right.score || left.letterIndex - right.letterIndex);
        return candidates[0] || null;
    }

    function staffPositionForPitch(pitch, fifths) {
        const spelling = spellPitch(pitch, fifths);
        return spelling ? spelling.octave * 7 + spelling.letterIndex : NaN;
    }

    function accidentalForPitch(pitch, fifths) {
        const spelling = spellPitch(pitch, fifths);
        if (!spelling) return null;
        const keyAlteration = keyAlterations(fifths)[spelling.letter];
        if (spelling.alteration === keyAlteration) return null;
        if (spelling.alteration === 0 && keyAlteration !== 0) return '♮';
        if (spelling.alteration < 0) return '♭';
        if (spelling.alteration > 0) return '♯';
        return null;
    }

    /** Assign each shared score id's rendered glyphs to its pitches, before hand filtering. */
    function assignGlyphPitches(glyphs, pitchesById) {
        const groups = new Map();
        (glyphs || []).forEach(glyph => {
            const id = String(glyph.id ?? '');
            if (!id || !Object.prototype.hasOwnProperty.call(pitchesById || {}, id)) return;
            const group = groups.get(id) || [];
            group.push(glyph);
            groups.set(id, group);
        });

        const assigned = [];
        groups.forEach((notes, id) => {
            const pitches = (pitchesById[id] || []).map(value => typeof value === 'object'
                ? { pitch: Number(value.pitch), hand: value.hand }
                : { pitch: Number(value), hand: null })
                .filter(value => Number.isFinite(value.pitch));
            notes.sort((a, b) => Number(a.y) - Number(b.y));
            const remainingNotes = notes.slice();
            const remainingPitches = pitches.slice();
            for (const [hand, staff] of [['right', 'treble'], ['left', 'bass']]) {
                const matchingPitches = remainingPitches.filter(value => value.hand === hand)
                    .sort((a, b) => b.pitch - a.pitch);
                const matchingNotes = remainingNotes.some(note => note.staff)
                    ? remainingNotes.filter(note => note.staff === staff)
                    : remainingNotes.length > matchingPitches.length
                        ? hand === 'left' ? remainingNotes.slice(-matchingPitches.length) : remainingNotes.slice(0, matchingPitches.length)
                        : remainingNotes.slice();
                for (let index = 0; index < Math.min(matchingPitches.length, matchingNotes.length); index += 1) {
                    const value = matchingPitches[index], note = matchingNotes[index];
                    assigned.push({ ...note, pitch: value.pitch });
                    remainingNotes.splice(remainingNotes.indexOf(note), 1);
                    remainingPitches.splice(remainingPitches.indexOf(value), 1);
                }
            }
            remainingPitches.sort((a, b) => b.pitch - a.pitch);
            for (let index = 0; index < Math.min(remainingNotes.length, remainingPitches.length); index += 1) {
                assigned.push({ ...remainingNotes[index], pitch: remainingPitches[index].pitch });
            }
        });
        return assigned;
    }

    function closestExpectedEntry(entries, pitch) {
        return (entries || []).reduce((best, entry) =>
            !best || Math.abs(entry.pitch - pitch) < Math.abs(best.pitch - pitch) ? entry : best, null);
    }

    /** Preserve both engraved locations when the same pitch occurs on two staves. */
    function closestExpectedEntries(entries, pitch) {
        const closest = closestExpectedEntry(entries, pitch);
        if (!closest) return [];
        return entries.filter(entry => entry.pitch === closest.pitch);
    }

    function closestStaffToReference(staves, reference) {
        return (staves || []).reduce((best, staff) =>
            !best || Math.abs(staff.center - reference.y) < Math.abs(best.center - reference.y) ? staff : best, null);
    }

    function matrixAtOffset(matrix, dx, dy) {
        return {
            a: matrix.a, b: matrix.b, c: matrix.c, d: matrix.d,
            e: matrix.e + dx, f: matrix.f + dy,
        };
    }

    function invertMatrix(matrix) {
        const determinant = matrix.a * matrix.d - matrix.b * matrix.c;
        if (!Number.isFinite(determinant) || determinant === 0) return null;
        return {
            a: matrix.d / determinant,
            b: -matrix.b / determinant,
            c: -matrix.c / determinant,
            d: matrix.a / determinant,
            e: (matrix.c * matrix.f - matrix.d * matrix.e) / determinant,
            f: (matrix.b * matrix.e - matrix.a * matrix.f) / determinant,
        };
    }

    function multiplyMatrices(left, right) {
        return {
            a: left.a * right.a + left.c * right.b,
            b: left.b * right.a + left.d * right.b,
            c: left.a * right.c + left.c * right.d,
            d: left.b * right.c + left.d * right.d,
            e: left.a * right.e + left.c * right.f + left.e,
            f: left.b * right.e + left.d * right.f + left.f,
        };
    }

    /** Convert an element's screen CTM back into the root SVG's user coordinates. */
    function matrixRelativeToRoot(rootScreenMatrix, noteScreenMatrix) {
        const screenToRoot = invertMatrix(rootScreenMatrix);
        return screenToRoot ? multiplyMatrices(screenToRoot, noteScreenMatrix) : null;
    }

    function matrixText(matrix) {
        return 'matrix(' + [matrix.a, matrix.b, matrix.c, matrix.d, matrix.e, matrix.f].join(',') + ')';
    }

    return {
        accidentalForPitch,
        assignGlyphPitches,
        closestExpectedEntry,
        closestExpectedEntries,
        closestStaffToReference,
        keyAlterations,
        matrixAtOffset,
        matrixRelativeToRoot,
        matrixText,
        spellPitch,
        staffPositionForPitch,
    };
}());

if (typeof module !== 'undefined' && module.exports) module.exports = PianoTrainerScoreRendering;
