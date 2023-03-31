# Jukebox

Browser based playlist generation from a media directory served from
NGINX.

## Setup and Deployment

Create a release build

```
./shadow-cljsw release app
```

Copy the following files from a successful build:

- `resources/public/index.html`
- `target/main.js`

to your web server into a directory (e.g. `/jukebox` relative to your
document root).

Next configure your NGINX to serve a virtual `/jukebox/_media` directory
as JSON from your file system with your music:


```
location /jukebox/_media {
	alias /ztank/media;
	autoindex on;
	autoindex_format json;
}
```

Reload the NGINX and navigate to your server's `/jukebox` URL.  Enjoy!


## Development

Start `shadow-cljs` for development:

```
./shadow-cljsw watch app
```

Open site in browser: http://localhost:3449/

Connect from vim:

```
:Piggieback :app
```

## Deployment

See `Makefile` for hints.
