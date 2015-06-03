/**
 * Created by razvanc on 02/05/2014.
 */

//https://github.com/yuku-t/jquery-textcomplete

// CA options for {{
var braTagsTEST ={
  'img' : 'URL',
  'video' : 'URL',
  'ad' : ['squaretop', 'squareright'],
  'ad1' : [
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

// CA options for {{
var braDomain = [
  'img URL',
  {'ad1' : [
    {'squaretop' : ['a', 'b']},
    'squareright'
  ]},
  'ad [squaretop|squareright]',
  'video URL',
  'photo URL',
  'slideshow URL',
  'link.img URL',
  'feed.rss URL',
  'tag name',
  'xpl path-expression',
  'section',
  'code [language]',
  'red text-to-redify',
  'roles ',
  'fiddle',
  'by', 'club',
  'where', 'at', 'place', 'venue',
  'loc:ll|s|url',
  'when', 'on', 'date',
  'rk',
  'widget',
  'f',
  'r1.delimited',
  'r1.table',
  'template',
  'red',
  'def name:signature',
  'lambda name:signature',
  'call'
];

var braTags = optsToDomain (braDomain);
var dTags = optsToDomain (braDomain.concat(dotTags));

// CA options for by:
var byTags = [
  'tomorow',
  'monday',
  'tuesday',
  'wednesday',
  'thursday',
  'friday'
];

// contains
function cont(value){
  return value.indexOf(term) == 0;
}

var topics=[]; // populate with options for topics
var ltopics=[]; // topics lowercase
var contacts=[];

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

$('#content').textcomplete([
  { // braTags
    match: /(\{\{)([\w ]*)$/,
    search: function (term, callback) {
      var terms = term.split(' ');
      var opts = getOptions(braTags, terms, 0); // options
      //if (opts.length > 0)
        callback(opts, false);// false means this array is all the data
      //else
      //  callback(['}}'], false);// false means this array is all the data
    },
    replace: function (value) {
      // double space is a marker for the FUTURE options
      var xvalue = value.replace(/(\w)  .*/, '\$1');
      if(xvalue.indexOf(' ') >= 0)
        return ['\{\{' + xvalue + ' ', ''];
      else
        // first time, only one term, insert }}
        return ['\{\{' + xvalue + ' ', '\}\}'];
    },
    cache: false
  },
  { // dotTags
    match: /(^|\s)\.([\w ]*)$/,
    search: function (term, callback) {
      var terms = term.split(' ');
      callback(getOptions(dTags, terms, 0), false);// false means this array is all the data
    },
    replace: function (value) {
      //return '$1.' + value.replace(/(\w)[: ].*/, '\$1') + ' ';
      return '$1.' + value.replace(/(\w)  .*/, '\$1') + ' ';
    },
    cache: false
  },
  { // sqbraTags
    match: /(\[\[)([\w| ]*)$/,
    search: function (term, callback) {
      callback(topics.filter(function(value, j){
        var lterm = term.toLowerCase()
        return ltopics[j].indexOf(lterm) == 0;
      }), topics.length <= 0); // false means this array is all the data
      if(topics.length <= 0)
        $.getJSON('/wikie/options', { q: term })
            .done(function (resp) {
              topics = resp;
              for(i=0; i<topics.length; i++) ltopics[i] = topics[i].toLowerCase();
              callback(topics.filter(function(value, j){
                return ltopics[j].indexOf(term) == 0;
              }))
            })
            .fail(function (){ callback([]); });
    },
    replace: function (value) {
      return ['\[\[' + value + '\]\]', ''];
    },
    cache: false
  },
  {
    match: /(to):(\w*)$/,
    search: function (term, callback) {
      callback(contacts.filter(function(value) {
        return value.indexOf ( term ) == 0 ;
      }), contacts.length <= 0); // false means this array is all the data
      if(contacts.length <= 0)
        $.getJSON('/notes/contacts', { q: term })
            .done(function (resp) {
              contacts = resp;
              callback(contacts.filter(function(value){
                return value.indexOf(term) == 0;
              }))
            })
            .fail(function (){ callback([]); });
    },
    replace: function (value) {
      return 'to:' + value + ' ';
    },
    cache: false
  }
]);

