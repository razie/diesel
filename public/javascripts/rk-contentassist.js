/**
 * Created by razvanc on 02/05/2014.
 */

//https://github.com/yuku-t/jquery-textcomplete

// sample json domain - this format feeds into the drop down
var braTagsTEST ={
  'img' : 'URL',
  'ad' : ['squaretop', 'squareright'],
  'test1' : [
    {'squaretop' : ['a', 'b']},
    'squareright'
    ],
  's1' : {
    'name' : ['first', 'last'],
    'age' : ''
  },
  's2' : {
    'name' : ['first',
      {'last' : ['a', 'b']}
      ],
    'age' : ''
  },
  'lambda' : 'name:signature',
  'call' : ''
};

// sample domain spec - you can turn it into json with optsToDomain(x)
var braDomainTest = [
  'img URL',
  {'test1' : [ // this is in json
    {'squaretop' : ['a', 'b']},
    'squareright'
  ]},
  'ad [squaretop|squareright]', // optional with options
  'xpl path-expression',
  'section',
  'code [language]',
  'red text-to-redify',
  'loc:ll|s|url'            // options
];

//var braTags = optsToDomain (braDomain);
//var dTags = optsToDomain (braDomain.concat(dotTags));

/** enumerate the props of the structure */
function enumProps (o) {
  var r = [];
  for (var key2 in o) {
    if (o.hasOwnProperty(key2)) {
      r = r.concat(key2);
    }
  }
  return r;
}

/**
 * get current options from domain o, for the terms already typed.
 *
 * the domain can be a string, a JSON array or an object (tree of DOM options)
 *
 * tidx - recursive index in terms, as I recursively search through the DOM
 */
function getOptions (o, terms, tidx) {
  var ret = [];

  if(tidx == terms.length-1) {
    // last one

    if(typeof o == "string") {
      // done - maybe evaluate default values or expressions ??
    } else if(Array.isArray(o)) {
      ret = o.filter(function(value){
        return (typeof value == 'string' && value.indexOf(terms[tidx]) == 0) || (typeof value != 'string');
      }).map(function(x){
        if(typeof x == 'string') return x;
        else return enumProps(x);
      });
    } else {
      for (var key in o) {
        // starts with prop or is prop and is last in line of props
        if (o.hasOwnProperty(key) && key.indexOf(terms[tidx]) == 0) {
          var val = o[key];
            if(typeof val == "string") {
              ret = ret.concat(key + '  ' + val);
            } else if(Array.isArray(val)) {
              var r = val.filter(function(value){return true;}).map(function(x){
                if(typeof x == 'string') return x;
                else if(Array.isArray(x)) return x;
                else return enumProps(x);
              });

              ret = ret.concat(key + '  ' + r);
            } else {
              ret = ret.concat(key + '  ' + enumProps(val));
            }
        }
      }
    }
  } else {
    // recurse if i can
    if(typeof o == "string") {
      // done - maybe evaluate default values or expressions ??
    } else if(Array.isArray(o)) {
      for (var i = 0; i < o.length; i++) {
        var val = o[i];
        if(typeof val != "string" && ret.length == 0) {
          // flatten structure in array - don't increase index, keep looking for same key
          ret = getOptions(val, terms, tidx)//.map(function(x){return terms[tidx]+' '+x;});
        }
      }

      if(ret.length == 1 && ret[0].lengh == 0)
        ret = [];
    } else {
      for (var key in o) {
        // starts with prop or is prop and is last in line of props
        if (o.hasOwnProperty(key) && key.indexOf(terms[tidx]) == 0) {
          var val = o[key];
          if(key == terms[tidx]) {
            // more to find
            ret = getOptions(val, terms, tidx+1).map(function(x){return terms[tidx]+' '+x;});
            }
          }
        }
      }
  }

  return ret;
}

/** convert a string of options into a structure */
function optsToDomain (o) {
  var res = {};

  for (var i = 0; i < o.length; i++) {
    if(typeof o[i] == "string") {
      var keys = o[i].split(' ');
      if(keys.length <= 1) {
        res[keys[0]]='';
      } else if (keys[1].indexOf('[') >= 0) {
        var k2 = keys[1].replace(/^\[/, '').replace(/\]$/, '');
        if(k2.indexOf('|') >= 0) {
          var karr = k2.split('|');
          res[keys[0]]= optsToDomain(karr);
        } else {
          res[keys[0]]= keys[1];
        }
      } else {
        res[keys[0]]= keys[1];
      }
    } else {
        for (var key in o[i]) {
          if (o[i].hasOwnProperty(key))
            res[key]= o[i][key];
        }
    }
    }

  return res;
}

// see wikiEdit.scala.html for sample usage of the content assist
