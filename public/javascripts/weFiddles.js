/**
 * Created by razvanc on 02/05/2014.
 */

var razuri = function(s) {
  return encodeURI(s.replace(/;/g,'%3B').replace(/\+/g,'%2B').replace(/script/g,'scrRAZipt'));
};

var encscr = function(s) {
  return s.replace(/'/g,'%27');
};

var decscr = function (s) {
  return s.replace(/%27/g,'\'');
};


