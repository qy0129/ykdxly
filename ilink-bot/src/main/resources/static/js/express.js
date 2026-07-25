(function(){
  'use strict';

  var data=window.__EXPRESS__||{};
  var progress=getComputedStyle(document.body).getPropertyValue('--progress').trim()||'0%';
  var fill=document.querySelector('.fill');
  var marker=document.querySelector('.marker');
  requestAnimationFrame(function(){
    if(fill)fill.style.width=progress;
    if(marker)marker.style.left=progress;
  });

  if(!data.mapEligible||!data.token)return;
  var attempts=0;
  function loadMap(){
    fetch('/express/map/'+encodeURIComponent(data.token),{cache:'no-store'})
      .then(function(response){
        if(response.status===202&&attempts++<8){setTimeout(loadMap,1500);return null;}
        if(!response.ok)return null;
        return response.blob();
      })
      .then(function(blob){
        if(!blob)return;
        var image=document.getElementById('routeMap');
        var section=document.getElementById('mapSection');
        image.src=URL.createObjectURL(blob);
        section.hidden=false;
      })
      .catch(function(){});
  }
  loadMap();
})();
