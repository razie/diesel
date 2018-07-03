/**
 * requires require
 */


//==================================== wikilike

var wikiVoted=false;

// simple protection for multiple clicks
function likeWiki (how) {
  require(['jquery'], function($){
  if(! wikiVoted) {
    $.ajax(
      '@routes.Wikie.like(wid)', {
        type: 'POST',
        data: $.param({
          how : ''+how
        }),
        contentType: 'application/x-www-form-urlencoded',
        success: function(data) {
          // update data.likesCount and datadislikesCount
        },
        error  : function(x) {
          console.log( "ERR "+JSON.stringify(x));
        }
      });
    $(".voteLikeThanks").text("Thank you!");
    $(".voteLikeThanks").show(2000);
    $(".voteLikeThanks").hide(2000);
    wikiVoted=true;
  } else {
    $(".voteLikeThanks").text("Already voted!");
    $(".voteLikeThanks").show(2000);
    $(".voteLikeThanks").hide(2000);
  }
  });
};

withJquery(function() {
  require(['jquery'], function($){
    $(".voteLike").html($("#voteLike").html())
});
});


//=============== testing

function log(msg) {
  console.log(msg);
}

