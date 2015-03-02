/**
 * Created by razvanc on 02/05/2014.
 */

//https://github.com/yuku-t/jquery-textcomplete

// CA options for . come from notesmain

// CA options for {{
var braTags =[
  'img URL',
  'video URL',
  'photo URL',
  'tag name',
  'xpl path-expression',
  'section',
  'code [language]',
  'red text-to-redify',
  'roles',
  'fiddle',
  'by', 'club',
  'where', 'at', 'place', 'venue',
  'loc:ll|s|url',
  'when', 'on', 'date',
  'ad [squaretop|squareright]',
  'rk',
  'widget',
  'f',
  'r1.delimited',
  'r1.table',
  'template',
  'slideshow',
  'def name:signature',
  'lambda name:signature',
  'call'
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

// contains
function cont(value){
  return value.indexOf(term) == 0;
}

var topics=[]; // populate with options for topics
var ltopics=[]; // topics lowercase
var contacts=[];

$('#content').textcomplete([
  { // braTags
    match: /(\{\{)(\w*)$/,
    search: function (term, callback) {
      callback(braTags.filter(function(value){
        return value.indexOf(term) == 0;
      }), false); // false means this array is all the data
    },
    replace: function (value) {
      return ['\{\{' + value.replace(/(\w)[: ].*/, '\$1') + ' ', '\}\}'];
    },
    cache: false
  },
  { // dotTags
    match: /(^|\s)\.(\w*)$/,
    search: function (term, callback) {
      callback(dotTags.filter(function(value){
        return value.indexOf(term) == 0;
      }), false); // false means this array is all the data
      //$.getJSON('/search', { q: term })
      //.done(function (resp) { callback(resp); })
      //.fail(function ()     { callback([]);   });
    },
    replace: function (value) {
      return '$1.' + value.replace(/(\w)[: ].*/, '\$1') + ' ';
    },
    cache: false
  },
  { // sqbraTags
    match: /(\[\[)((\w| )*)$/,
    search: function (term, callback) {
      callback(topics.filter(function(value, j){
        return ltopics[j].indexOf(term) == 0;
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
  },
  {
    match: /(by):(\w*)$/,
    search: function (term, callback) {
     callback(byTags.filter(function(value){
        return value.indexOf(term) == 0;
      }), false); // false means this array is all the data
    },
    replace: function (value) {
      return 'by:' + value + ' ';
    },
    cache: false
  },
  {
    match: /(^|\s):(\w*)$/,
    search: function (term, callback) {
      var regexp = new RegExp('^' + term);
      callback($.grep(emojies, function (emoji) {
        return regexp.test(emoji);
      }));
    },
    replace: function (value) {
      return '$1:' + value + ': ';
    }
  }
]);

