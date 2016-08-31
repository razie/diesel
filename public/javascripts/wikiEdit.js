/**
 * Created by razvanc on 02/05/2014.
 */

function parseAndRender() {
  var commonmark = window.commonmark;
  var reader = new commonmark.Parser();
  var writer = new commonmark.HtmlRenderer();

  var s = preProcess($("#content").val()); // parsed is a 'Node' tree
  var parsed = reader.parse(s); // parsed is a 'Node' tree
  // transform parsed if you like...
  var result = writer.render(parsed); // result is a String

  $("#weOutputData").html(result);
}

function refreshServer() {
  var content = $("#content").val();
  var tags = $("#tags").val();
  $.post("/wikie/preview/@wid.wpath", {'content' : content, 'tags' : tags}, function(data) {
    $("#weOutputData").html(data);
  })
}

/** can this be rendered in page? */
function canRenderInPage(cat,name,content,tags) {
  if (
    /^\.def|^\.class/.test(content) ||
    /dsl/.test(tags)
  )
    return false;

  return true;
}

/** pre process the markdown content before the JS common mark embedded parser */
function preProcess(s) {
  var res = s;

  var bottom = "";

  if (s.match(/\{\{code/) || s.match(/```/))
    bottom = bottom + '<script src="/assets/vendor/hijs.js"></script>';

  // [\s\S]*  matches anything across multi lines (including \n)
  // [\s\S]*? makes it non-greedy (so won't match and group 5 blocks into one

  // fiddles

  res = res.replace(/\{\{fiddle[^}]*\}\}[\s\S]*?\{\{\/fiddle\}\}/g, '`{{fiddle... hidden}}`');
  res = res.replace(/(\{\{code[^}]*\}\})([\s\S]*?)(\{\{\/code\}\})/g, '<pre><code>' + '\$2' + '</code></pre>');

  // props

  res = res.replace(/(\{\{\.[^}]+\}\})/g, '');
  res = res.replace(/(\{\{[^.][^}]+\}\})/g, '`\$1`');

  // links

  res = res.replace(/(\[\[\[)([^\]\|]+)(\]\]\])/g, '<a href="http://en.wikipedia.org/wiki/\$2">\$2</a>');
  res = res.replace(/(\[\[)([^\]\|]+)(\]\])/g, '<a href="/wiki/\$2">\$2</a>');
  res = res.replace(/(\[\[)([^\]\|]+)\|([^\]]+)(\]\])/g, '<a href="/wiki/\$2">\$3</a>');

  // url links

  res = res.replace(/(\[)([^\] ]+) ([^\]]+)(\])/g, '<a href="\$2">\$3</a>');
  res = res.replace(/(\[)([^\]]+)(\])([^(])/g, '<a href="\$2">\$2</a>\$4');

  return res+bottom;
}


