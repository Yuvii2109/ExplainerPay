// Section 17.7, the budget that is statically enforceable.
//
// Animate on the compositor only: transform and opacity. Never width, height, top or left.
// A CI check is worth more than a comment, because the comment does not fail a build.

import { readFileSync } from "node:fs";

const css = readFileSync(new URL("../src/app/globals.css", import.meta.url), "utf8");
const forbidden = ["width", "height", "top", "left", "right", "bottom", "margin", "padding"];
const failures = [];

// Properties named inside a @keyframes block are the ones the compositor cannot handle alone.
for (const [, name, body] of css.matchAll(/@keyframes\s+([\w-]+)\s*\{([\s\S]*?)\n\}/g)) {
  for (const property of body.matchAll(/^\s*([a-z-]+)\s*:/gm)) {
    if (forbidden.includes(property[1])) {
      failures.push(`@keyframes ${name} animates ${property[1]}`);
    }
  }
}

// A transition that names a forbidden property costs a layout on every frame.
for (const [, value] of css.matchAll(/transition\s*:\s*([^;]+);/g)) {
  for (const word of value.split(/[\s,]+/)) {
    if (forbidden.includes(word)) {
      failures.push(`transition animates ${word}`);
    }
  }
}

if (failures.length) {
  console.error("frame budget: animations must use transform and opacity only");
  failures.forEach((f) => console.error("  " + f));
  process.exit(1);
}

const keyframes = [...css.matchAll(/@keyframes\s+([\w-]+)/g)].map((m) => m[1]);
console.log(
  `frame budget ok: ${keyframes.length} keyframe animations (${keyframes.join(", ")}), ` +
    `compositor properties only`,
);
