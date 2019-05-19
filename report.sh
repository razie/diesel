# Report how the day went, from the log file

if [[ $1 != "" ]]; then
  X=$1
else
  X=`date --date="1 days ago" +%Y-%m-%d`
fi

if [[ $X == "today" ]]; then
  X=`date --date="today" +%Y-%m-%d`
  PAT="log.log *$X*"
else
  PAT="*$X*"
fi

echo "Logfile pattern $PAT"
echo "-"
echo "------------------------ Report for $X"

cd logs

# FILES=`ls -rt *$X*`

echo "Served LF.STOP.PAGE:  " `grep LF.STOP.PAGE $PAT | grep -v "razadmin/ping/x" | wc -l`
echo "Average LF.STOP.PAGE: " `grep LF.STOP.PAGE $PAT | grep -v "razadmin/ping/x" | sed 's/.*LF.STOP.PAGE.* took \([0-9]*\)ms.*/\1/g' | grep -E '[0-9][0-9]+.*' | awk '{s+=$1}END{print "",s/NR}' RS="\n"`
echo "top 10 LF.STOP.PAGE (duration): "
grep LF.STOP.PAGE $PAT | grep -v "razadmin/ping/x" | sed 's/.*LF.STOP.PAGE .* \(.*\) took \([0-9]*\)ms.*/\2 \1/g' | sort -n -r --key=1 | sort -r --key=2 | uniq --skip-fields=1 | sort -b -n -r | head -20
echo "-"
echo "WIKI_CACHE hits: " `grep WIKI_CACHE $PAT | wc -l`
echo "-"
echo "WIKI_CACHE hits: "
grep WIKI_CACHE $PAT | sed 's/.*WIKI_CACHED//g' | sort | uniq -c | sort -b -n -r | head -20
echo "-"

echo "Report for $X ends"
echo "------------------------ Report for $X"

cd ..

