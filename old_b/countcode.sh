# Use countcode.sh dir
D1=~/w/racerkidz
D2=~/w/coolscala
D3=~/w/snakked

echo "java      " `find $D1 $D2 $D3 -name "*.java" -not -path "*/target/*" -exec wc -l {} \; | awk '{ SUM += $1} END { print + SUM }'`
echo "scala     " `find $D1 $D2 $D3 -name "*.scala" -not -path "*/target/*" -exec wc -l {} \; | awk '{ SUM += $1} END { print SUM }'`
echo "scala.html" `find $D1 $D2 $D3 -name "*.scala.html" -not -path "*/target/*" -exec wc -l {} \; | awk '{ SUM += $1} END { print SUM }'`

