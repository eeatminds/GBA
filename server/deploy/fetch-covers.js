// Puts the box art of the games on the games page into the covers folder:
//
//   node deploy/fetch-covers.js /etc/gba-rooms/covers
//
// From the libretro thumbnails library (the one the app shows game covers from). Each game is
// fetched once: a picture already in the folder (one the owner put there too) is kept. Shrunk to
// a small WebP picture when cwebp is installed (the install script installs it), so the page stays
// light on phones; otherwise kept as the PNG.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { COVER_TYPES, GAME_FAMILIES, coverSourceUrl } from '../src/server.js';

const dir = process.argv[2] ?? '/etc/gba-rooms/covers';
const SIZE = '256';

function hasCwebp() {
  try {
    execFileSync('cwebp', ['-version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

const shrink = hasCwebp();
fs.mkdirSync(dir, { recursive: true });
for (const game of GAME_FAMILIES.flatMap((family) => family.games)) {
  const url = coverSourceUrl(game);
  if (!url || Object.keys(COVER_TYPES).some((ext) => fs.existsSync(path.join(dir, `${game.id}.${ext}`)))) continue;
  const download = path.join(dir, `.${game.id}.download.png`);
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(30_000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const png = Buffer.from(await response.arrayBuffer());
    if (png.subarray(1, 4).toString('latin1') !== 'PNG') throw new Error('not a PNG picture');
    fs.writeFileSync(download, png);
    if (shrink) {
      execFileSync('cwebp', ['-quiet', '-q', '82', '-resize', SIZE, SIZE, download, '-o', path.join(dir, `${game.id}.webp`)]);
      fs.rmSync(download);
    } else {
      fs.renameSync(download, path.join(dir, `${game.id}.png`));
    }
    console.log(`Cover added: ${game.name}`);
  } catch (error) {
    fs.rmSync(download, { force: true });
    console.log(`Cover not added for ${game.name} (${error.message}); the page shows its emblem.`);
  }
}
