## Clean all sbt stuff

DIR=/Users/raz/w/racerkidz

rm -rf $DIR/target
rm -rf $DIR/bin
rm -rf $DIR/modules/wcommon/target
rm -rf $DIR/modules/wiki/target
rm -rf $DIR/project/target
rm -rf $DIR/project/project

rm -rf ~/.ivy2/cache/*

p clean cleanFiles update

