/**
 * Created by razvanc on 02/05/2014.
 */

// CA options for {{
var braDomain = [
  'alert [green|blue|yellow|red]',
  'img URL',
  'img.small URL',
  'img.medium URL',
  'img.large URL',
  'photo URL',
  'photo.small URL',
  'photo.medium URL',
  'photo.large URL',
  {'noAds' : ['true']},
  {'test1' : [
    {'squaretop' : ['a', 'b']},
    'squareright'
  ]},
  {'layout' : [
    'Play:wiki.layout.div12',
    'Play:wiki.layout.div12FullPage',
    'Play:wiki.layout.div12Cool',
    'Play:wiki.layout.div12Plain',
    'Play:wiki.layout.div9Plain',
    'Play:wiki.layout.div9Ad'
  ]},
  'ad [squaretop|squareright]',
  'video URL',
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
  'call',
  'footref', 'footnote',
  '/section'
];


// CA options for by:
var byTags = [
  'tomorow',
  'monday',
  'tuesday',
  'wednesday',
  'thursday',
  'friday'
];

var topics=[]; // populate with options for topics
var ltopics=[]; // topics lowercase

var CA_TC_braTags = function(braTags) {
  return { // braTags
    match: /(\{\{)([\w /]*)$/,
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
      if (xvalue.indexOf(' ') >= 0)
        return ['\{\{' + xvalue + ' ', ''];
      else if (xvalue.indexOf('/') == 0)
        return ['\{\{' + xvalue, '\}\}'];
      else
      // first time, only one term, insert }}
        return ['\{\{' + xvalue + ' ', '\}\}'];
    },
    cache: false
  };
};

// CA options for [[
var sqbraStatics = [
  'include:WID',
  'includeWithSection:WID',
  'alias:WID'
];

// this is not a constructor - it will get its domain from remote
var CA_TC_sqbraTags = { // sqbraTags
  match: /(\[\[)([\w| ]*)$/,

  search: function (term, callback) {
    var lterm = term.toLowerCase();
    var filterTopics = function(value, j) {
      return ltopics[j].indexOf(lterm) >= 0;
    };
    var filterStatics = function(value, j) {
      return sqbraStatics[j].indexOf(lterm) >= 0;
    };

    if(topics.length <= 0)
      //$.getJSON('/wikie/options', { q: term , realm : realm })
      $.getJSON('/wikie/options', { q: '' , realm : realm })
        .done(function (resp) {
          topics = resp;
          for(i=0; i<topics.length; i++) ltopics[i] = topics[i].toLowerCase();
          callback(topics.filter(filterTopics).concat(sqbraStatics.filter(filterStatics)));
        })
        .fail(function (){ callback([]); });
    else
      callback(topics.filter(filterTopics).concat(sqbraStatics.filter(filterStatics)), false);
      // false means this array is all the data
    },

  replace: function (value) {
    return ['\[\[' + value + '\]\]', ''];
  },

  cache: false
};


var contacts=[];

// this is not a constructor - it will get its domain from remote
var CA_TC_to = {
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
};


var CA_TC_dotTags = function(dTags) {
  return { // dotTags
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
  }
};

