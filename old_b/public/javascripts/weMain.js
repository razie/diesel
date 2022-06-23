/**
 * requires require
 */

//==================================== wikilike

var wikiVoted=false;


withJquery(function() {
  require(['jquery'], function($){
    $(".voteLike").html($("#voteLike").html())
});
});


//=============== testing

function log(msg) {
  console.log(msg);
}

