all:

release:
	lein do clean, cljsbuild once min

deploy: release
	rsync -av --delete resources/public/ hwergelmir:/usr/local/www/data/jukebox/

.PHONY: all release deploy
