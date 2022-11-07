var m = [20, 120, 20, 120],
    w = 1280 - m[1] - m[3],
    h = 800 - m[0] - m[2],
    i = 0,
    root;

var tree = d3.layout.tree()
    .size([h, w]);

var diagonal = d3.svg.diagonal()
    .projection(function(d) { return [d.y, d.x]; });

var svg = d3.select("#body").append("svg:svg")
    .attr("width", w + m[1] + m[3])
    .attr("height", h + m[0] + m[2])
  .append("svg:g")
    .attr("transform", "translate(" + m[3] + "," + m[0] + ")");

var upNodes=[], downNodes=[];

var PAGE=20;


d3.json(razUrl, function(json) {
  root = json;
  root.x0 = h / 2;
  root.y0 = 0;

  function toggleAll(d) {
    if (d.children) {
      d.children.forEach(toggleAll);
      toggle(d);
    }
  }

  // leave only a page of nodes, put rest in downnodes
  if(root.children.length > PAGE) {
     downNodes = root.children.slice(PAGE,root.children.length-1)
     root.children = root.children.slice(0,PAGE)
  }
  
  // Initialize the display to show a few nodes.
  root.children.forEach(toggleAll);
//  toggle(root.children[9].children[0]);

  update(root);
   
});

function scrollUp() {
   if (downNodes.length > 0) {
      upNodes.push(root.children.shift())
      root.children.push(downNodes.shift())
      update(root)
   }
}

function scrollDown() {
   if (upNodes.length > 0) {
      downNodes.unshift(root.children.pop())
      root.children.unshift(upNodes.pop())
      update(root)
   }
}

function update(source) {
  var duration = d3.event && d3.event.altKey ? 5000 : 500;

  // Compute the new tree layout.
  var nodes = tree.nodes(root).reverse();

  // Normalize for fixed-depth.
  nodes.forEach(function(d) { d.y = d.depth * 180; });

  // Update the nodes
  var node = svg.selectAll("g.node")
      .data(nodes, function(d) { return d.id || (d.id = ++i); });

  // Enter any new nodes at the parent's previous position.
  var nodeEnter = node.enter().append("svg:g")
      .attr("class", "node")
      .attr("transform", function(d) { return "translate(" + source.y0 + "," + source.x0 + ")"; })
      .on("click", function(d) { toggle(d); update(d); });

  nodeEnter.append("svg:circle")
      .attr("r", 1e-6)
      .style("fill", function(d) { return d._children ? "lightsteelblue" : "#fff"; });

  var r = nodeEnter.append("svg:rect")
      .style("fill", "blue");

  nodeEnter.append("svg:text")
      .attr("x", function(d) { return d.children || d._children ? -10 : 10; })
      .attr("dy", ".35em")
      .attr("text-anchor", function(d) { return d.children || d._children ? "end" : "start"; })
      .text(function(d) { return d.name; })
      .style("fill-opacity", 1e-6)
  .on("click", function(x) {
     if(x.url===undefined) {} else window.location.href='http://localhost:9000/gapi/samples/g2?url='+x.url
     if(x.data.url===undefined) {} else window.location.href='http://localhost:9000/gapi/samples/g2?url='+x.data.url
    });

  // Transition nodes to their new position.
  var nodeUpdate = node.transition()
      .duration(duration)
      .attr("transform", function(d) { return "translate(" + d.y + "," + d.x + ")"; });

  nodeUpdate.select("circle")
      .attr("r", 10)//4.5)
      .style("fill", function(d) { return d._children ? "lightsteelblue" : "#fff"; });

  nodeUpdate.select("text")
      .style("fill-opacity", 1);

  // Transition exiting nodes to the parent's new position.
  var nodeExit = node.exit().transition()
      .duration(duration)
      .attr("transform", function(d) { return "translate(" + source.y + "," + source.x + ")"; })
      .remove();

  nodeExit.select("circle")
      .attr("r", 1e-6);

  nodeExit.select("text")
      .style("fill-opacity", 1e-6);

  // Update the links…
  var link = svg.selectAll("path.link")
      .data(tree.links(nodes), function(d) { return d.target.id; });

  // Enter any new links at the parent's previous position.
  link.enter().insert("svg:path", "g")
      .attr("class", "link")
      .attr("d", function(d) {
        var o = {x: source.x0, y: source.y0};
        return diagonal({source: o, target: o});
      })
    .transition()
      .duration(duration)
      .attr("d", diagonal);

  // Transition links to their new position.
  link.transition()
      .duration(duration)
      .attr("d", diagonal);

  // Transition exiting nodes to the parent's new position.
  link.exit().transition()
      .duration(duration)
      .attr("d", function(d) {
        var o = {x: source.x, y: source.y};
        return diagonal({source: o, target: o});
      })
      .remove();

  svg.append("path")
    .attr("d", "M 300 100 L 350 50 L 400 100 Z")
    .attr("stroke", "red")
    .attr("stroke-width", "2")
    .attr("fill", (upNodes.length > 0 ? "blue" : "gray"))
    .on("click", function(x) { 
       scrollDown() 
       });
  
  var dn = svg.append("path")
    .attr("d", "M 300 200 L 350 250 L 400 200 Z")
    .attr("stroke", "red")
    .attr("stroke-width", "2")
    .attr("fill", (downNodes.length > 0 ? "blue" : "gray"))
    .on("click", function(x) { 
       scrollUp() 
       });

  // Stash the old positions for transition.
  nodes.forEach(function(d) {
    d.x0 = d.x;
    d.y0 = d.y;
  });
}

//Toggle children.
function toggle(d) {
  if (d.children) {
    d._children = d.children;
    d.children = null;
  } else {
    d.children = d._children;
    d._children = null;
  }
}

//setInterval(function(){
//   scrollDown();
//}, 3000);
