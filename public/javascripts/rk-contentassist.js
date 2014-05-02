/**
 * Created by razvanc on 02/05/2014.
 */

//https://github.com/yuku-t/jquery-textcomplete

// CA options for .
var dotTags =[
  't tag1 tag2',
  'n name of this note',
  'a to:who by:date description',
  'email john@@whodunnit.com',
  'name name of contact'
];

// CA options for {{
var braTags =[
  'by', 'club',
  'where', 'at', 'place', 'venue',
  'loc:ll|s|url',
  'when', 'on', 'date',
  'xpl',
  'roles',
  'ad',
  'rk',
  'widget',
  'f',
  'r1.delimited',
  'r1.table',
  'section',
  'template',
  'img',
  'video',
  'photo',
  'slideshow',
  'fiddle',
  'code',
  'def',
  'lambda',
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

var contacts=[];

$('#content').textcomplete([
  { // beaTags
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

