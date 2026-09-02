#!/bin/bash

URL="$1"

curl https://api.brightdata.com/request \
-H "Content-Type: application/json" \
-H "Authorization: Bearer $BRIGHT_DATA_API_TOKEN" \
-d "{\"zone\": \"web_unlocker1\",\"url\": \"$URL\", \"format\": \"raw\"}"