/**
 * Created by razvanc on 02/05/2014.
 */

function parseAndRender(commonmark) {
  // var commonmark = window.commonmark;
  var reader = new commonmark.Parser();
  var writer = new commonmark.HtmlRenderer();

  var s = preProcess($("#content").val()); // parsed is a 'Node' tree
  var parsed = reader.parse(s); // parsed is a 'Node' tree
  // transform parsed if you like...
  var result = writer.render(parsed); // result is a String

  $("#weOutputData").html(result);
}

function refreshServer(wp, content, tags) {
  // var content = $("#content").val();
  // var tags = $("#tags").val();
  var markup = $("#markup").val();
  $.post("/wikie/preview/"+wp, {'content' : content, 'tags' : tags, 'markup' : markup}, function(data) {
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

  if (s.match(/\{\{code/) || s.match(/```/)) {
    bottom = bottom + '<script src="/assets/vendor/hijs.js"></script>';
    bottom = bottom + '<script src="/assets/vendor/hiscala.js"></script>';
  }

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


/** save the draft on the backend */
function saveSection(wpath,sectionType,sectionName,content,callback) {
  $.ajax(
    '/wikie/setSection/'+wpath, {
      type: 'POST',
      data: $.param({
        sectionType : sectionType,
        sectionName : sectionName,
        content : content
      }),
      timeout : 2000,
      contentType: 'application/x-www-form-urlencoded',
      success: function(data) {
        console.log( "OK "+data);
        if(typeof callback != 'undefined') callback();
      },
      error  : function(x) {
        showError("ERROR: Cannot save draft ["+JSON.stringify(x)+"]");
      }
    });
}

/** save the draft on the backend */
function anonSaveSection(wpath,sectionType,sectionName,content,callback) {
  $.ajax(
    '/diesel/anon/setSection/'+wpath, {
      type: 'POST',
      data: $.param({
    sectionType : sectionType,
    sectionName : sectionName,
        content : content
      }),
      timeout : 2000,
      contentType: 'application/x-www-form-urlencoded',
      success: function(data) {
        console.log( "OK "+data);
        if(typeof callback != 'undefined') callback(data);
      },
      error  : function(x) {
        console.log( "ERR "+JSON.stringify(x));
        if(typeof callback != 'undefined') callback(null, x);
      }
    });
}


////////////////////////// ACE

function attachAce(ace, id, content, light, onChange) {
  aceAttached = true;

  var langTools = ace.require("ace/ext/language_tools");
  var editor = ace.edit(id);

  editor.getSession().setValue(content);

  if(typeof light == 'undefined' || light) {
    editor.setTheme ( "ace/theme/crimson_editor" ) ;
  } else {
    editor.setTheme ( "ace/theme/twilight" ) ;
  }
  editor.getSession().setMode("ace/mode/nvp2");

  editor.setOptions({
    tabSize: 2,
    enableBasicAutocompletion: true,
    enableLiveAutocomplete:  true,
    enableLiveAutocompletion:  true
  });


  //var dotTags = [];
  //
  //$('#content').textcomplete([
  //  CA_TC_braTags (optsToDomain(braDomain)),
  //  CA_TC_sqbraTags,
  //  CA_TC_dotTags (optsToDomain(braDomain.concat(dotTags)))
  // ]);


  //todo can't have two editors with two completers...
  //todo I could simulate it with an IF inside instCompletions
  var domCompleter = {
    getCompletions: domCompl(false)
  };
  var instCompleter = {
    getCompletions: domCompl(true)
  };

  // delegate the keywords to the xtext generated completer
  var keyWordCompleter = {
    getCompletions: function(editor, session, pos, prefix, callback) {
      var state = editor.session.getState(pos.row);
      var completions = [];

      // raz: not interested except in DSL lines
//        if(session.getLine(pos.row).indexOf("$") == 0) {
      completions = session.$mode.getCompletions(state, session, pos, prefix);
//        }
      callback(null, completions);
    }
  };

  //  langTools.addCompleter(instCompleter);
  langTools.setCompleters([keyWordCompleter, instCompleter]);

  editor.commands.addCommand({
    name: "gui",
    bindKey: {win: "Ctrl-G", mac: "Command-G"},
    exec: codeGui
  });

  return editor;
}

function detachAce(aceEditor) {
  aceAttached = false;
  if(aceEditor) {
    aceEditor.destroy();
    aceEditor.remove();
  }
}


