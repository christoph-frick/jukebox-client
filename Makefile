all:

release:
	lein do clean, cljsbuild once min

deploy: release
	rsync -avn --delete resources/public/ hwergelmir:/usr/local/www/data/jukebox/

.PHONY: all release deploy
