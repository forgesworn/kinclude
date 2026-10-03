# Kinclude — one-page marketing site

Static page in the style of the Kindred site. No build step. The site is
`public/`, live at https://kinclude.app. The copy describes
the intended design; the product is not built.

## Preview

    python3 -m http.server -d sites/kinclude/public 8000

then open http://localhost:8000. Check it at 375px wide (no horizontal scroll)
and at desktop width.

## Deploy

Served by Cloudflare as a Worker with static assets (`kinclude-site`,
config in `wrangler.jsonc`), on the custom domains `kinclude.app` and
`www.kinclude.app`. Cloudflare manages the DNS records and TLS.

A push to `main` that touches `sites/kinclude/` deploys it automatically
(`.github/workflows/deploy-site.yml`), then checks that kinclude.app is serving
the new files. The workflow can also be run by hand from the Actions tab. To
deploy from a local checkout instead:

    cd sites/kinclude && npx wrangler deploy

## Files

- `public/index.html` — the page
- `public/styles.css` — the stylesheet
- `public/favicon.svg` — the favicon
- `public/robots.txt`, `public/sitemap.xml` — crawler files
- `wrangler.jsonc` — the Cloudflare Worker config
