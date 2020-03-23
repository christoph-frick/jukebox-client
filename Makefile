all:

extract:
	unzip -qqp `lein cp | sed -n 's/.*:\([^:]*antd[^:]*\):.*/\1/p'` cljsjs/antd/production/antd.min.inc.css > resources/public/css/antd.min.inc.css

release: extract
	lein do clean, cljsbuild once min

deploy: release redeploy

redeploy:
	rsync -av --delete resources/public/ hwergelmir:/usr/local/www/data/jukebox/

.PHONY: all extract release redeploy deploy
