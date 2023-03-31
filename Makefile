all:

release:
	./shadow-cljsw release app

deploy: release redeploy

redeploy:
	scp resources/public/index.html target/main.js hwergelmir:/usr/local/www/data/jukebox/

.PHONY: all extract release redeploy deploy
