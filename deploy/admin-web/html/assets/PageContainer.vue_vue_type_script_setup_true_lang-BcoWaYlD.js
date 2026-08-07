import{Y as y,af as J,x as A,q as M,d as K,u as ae,v as se,y as oe,c as d,a,b as h,e as r,D as u,A as g,w as p,G as L,F as T,I as z,m as k,r as U,a3 as re,ag as ne,ah as le,ae as P,z as $,H as ie,o as s,p as w,ai as de,aj as ce,h as he,i as pe,N as q,t as ue}from"./index-DLW7jusX.js";import{U as G}from"./utensils-Dr8uonEY.js";import{c as n}from"./createLucideIcon-CSOXZwCj.js";import{H as ye}from"./home-Ck16FUp-.js";function me(e){return Array.isArray(e)?e:e&&typeof e=="object"&&"records"in e?e.records??[]:[]}const xe={list:e=>y.get(`/notification/store/${e.storeId}`,{params:e}).then(t=>t.data),create:e=>y.post("/notification",e).then(t=>t.data),update:(e,t)=>y.put(`/notification/${e}`,t).then(i=>i.data),delete:e=>y.delete(`/notification/${e}`).then(t=>t.data),toggleStatus:(e,t)=>y.put(`/notification/${e}/status`,null,{params:{status:t}}).then(i=>i.data)},ke={list:()=>y.get("/store").then(e=>e.data),get:e=>y.get(`/store/${e}`).then(t=>t.data),create:e=>y.post("/store",e).then(t=>t.data),update:(e,t)=>y.put(`/store/${e}`,t).then(i=>i.data),delete:e=>y.delete(`/store/${e}`).then(t=>t.data),resetSecurityCode:e=>y.post(`/store/${e}/reset-security-code`).then(t=>t.data),getBranding:(e,t)=>y.get(`/store/${e}/branding`,{headers:t?{"If-None-Match":t}:{},validateStatus:i=>i===200||i===304}).then(i=>({data:i.data,status:i.status})),updateBranding:(e,t)=>y.put(`/store/${e}/branding`,t).then(i=>i.data),getCurrent:()=>y.get("/store/current").then(e=>e.data),switchTo:e=>y.post(`/store/${e}/switch`).then(t=>t.data)},ea={uploadImage:e=>{const t=new FormData;return t.append("file",e),y.post("/file/upload-image",t,{headers:{"Content-Type":"multipart/form-data"}}).then(i=>i.data)}};/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const ve=n("BarChart3Icon",[["path",{d:"M3 3v18h18",key:"1s2lah"}],["path",{d:"M18 17V9",key:"2bz60n"}],["path",{d:"M13 17V5",key:"1frdt8"}],["path",{d:"M8 17v-3",key:"17ska0"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const ge=n("BellIcon",[["path",{d:"M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9",key:"1qo2s2"}],["path",{d:"M10.3 21a1.94 1.94 0 0 0 3.4 0",key:"qgo35s"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const fe=n("BookOpenIcon",[["path",{d:"M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z",key:"vv98re"}],["path",{d:"M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z",key:"1cyq3y"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const be=n("Building2Icon",[["path",{d:"M6 22V4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v18Z",key:"1b4qmf"}],["path",{d:"M6 12H4a2 2 0 0 0-2 2v6a2 2 0 0 0 2 2h2",key:"i71pzd"}],["path",{d:"M18 9h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-2",key:"10jefs"}],["path",{d:"M10 6h4",key:"1itunk"}],["path",{d:"M10 10h4",key:"tcdvrf"}],["path",{d:"M10 14h4",key:"kelpxr"}],["path",{d:"M10 18h4",key:"1ulq68"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const _e=n("CalendarCheckIcon",[["path",{d:"M8 2v4",key:"1cmpym"}],["path",{d:"M16 2v4",key:"4m81vk"}],["rect",{width:"18",height:"18",x:"3",y:"4",rx:"2",key:"1hopcy"}],["path",{d:"M3 10h18",key:"8toen8"}],["path",{d:"m9 16 2 2 4-4",key:"19s6y9"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const we=n("ChefHatIcon",[["path",{d:"M6 13.87A4 4 0 0 1 7.41 6a5.11 5.11 0 0 1 1.05-1.54 5 5 0 0 1 7.08 0A5.11 5.11 0 0 1 16.59 6 4 4 0 0 1 18 13.87V21H6Z",key:"z3ra2g"}],["line",{x1:"6",x2:"18",y1:"17",y2:"17",key:"12q60k"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Me=n("ChevronRightIcon",[["path",{d:"m9 18 6-6-6-6",key:"mthhwq"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Ce=n("ClipboardCheckIcon",[["rect",{width:"8",height:"4",x:"8",y:"2",rx:"1",ry:"1",key:"tgr4d6"}],["path",{d:"M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",key:"116196"}],["path",{d:"m9 14 2 2 4-4",key:"df797q"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Ie=n("ClipboardListIcon",[["rect",{width:"8",height:"4",x:"8",y:"2",rx:"1",ry:"1",key:"tgr4d6"}],["path",{d:"M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2",key:"116196"}],["path",{d:"M12 11h4",key:"1jrz19"}],["path",{d:"M12 16h4",key:"n85exb"}],["path",{d:"M8 11h.01",key:"1dfujw"}],["path",{d:"M8 16h.01",key:"18s6g9"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Se=n("ClockIcon",[["circle",{cx:"12",cy:"12",r:"10",key:"1mglay"}],["polyline",{points:"12 6 12 12 16 14",key:"68esgv"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const je=n("DatabaseBackupIcon",[["ellipse",{cx:"12",cy:"5",rx:"9",ry:"3",key:"msslwz"}],["path",{d:"M3 12a9 3 0 0 0 5 2.69",key:"1ui2ym"}],["path",{d:"M21 9.3V5",key:"6k6cib"}],["path",{d:"M3 5v14a9 3 0 0 0 6.47 2.88",key:"i62tjy"}],["path",{d:"M12 12v4h4",key:"1bxaet"}],["path",{d:"M13 20a5 5 0 0 0 9-3 4.5 4.5 0 0 0-4.5-4.5c-1.33 0-2.54.54-3.41 1.41L12 16",key:"1f4ei9"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const ze=n("FileSpreadsheetIcon",[["path",{d:"M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",key:"1rqfz7"}],["path",{d:"M14 2v4a2 2 0 0 0 2 2h4",key:"tnqrlb"}],["path",{d:"M8 13h2",key:"yr2amv"}],["path",{d:"M14 13h2",key:"un5t4a"}],["path",{d:"M8 17h2",key:"2yhykz"}],["path",{d:"M14 17h2",key:"10kma7"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const $e=n("FileTextIcon",[["path",{d:"M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",key:"1rqfz7"}],["path",{d:"M14 2v4a2 2 0 0 0 2 2h4",key:"tnqrlb"}],["path",{d:"M10 9H8",key:"b1mrlr"}],["path",{d:"M16 13H8",key:"t4e002"}],["path",{d:"M16 17H8",key:"z1uh3a"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const qe=n("LayoutDashboardIcon",[["rect",{width:"7",height:"9",x:"3",y:"3",rx:"1",key:"10lvy0"}],["rect",{width:"7",height:"5",x:"14",y:"3",rx:"1",key:"16une8"}],["rect",{width:"7",height:"9",x:"14",y:"12",rx:"1",key:"1hutg5"}],["rect",{width:"7",height:"5",x:"3",y:"16",rx:"1",key:"ldoo1y"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Ae=n("LogOutIcon",[["path",{d:"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4",key:"1uf3rs"}],["polyline",{points:"16 17 21 12 16 7",key:"1gabdz"}],["line",{x1:"21",x2:"9",y1:"12",y2:"12",key:"1uyos4"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const He=n("MegaphoneIcon",[["path",{d:"m3 11 18-5v12L3 14v-3z",key:"n962bs"}],["path",{d:"M11.6 16.8a3 3 0 1 1-5.8-1.6",key:"1yl0tm"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Ve=n("MenuIcon",[["line",{x1:"4",x2:"20",y1:"12",y2:"12",key:"1e0a9i"}],["line",{x1:"4",x2:"20",y1:"6",y2:"6",key:"1owob3"}],["line",{x1:"4",x2:"20",y1:"18",y2:"18",key:"yk5zj1"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Be=n("MessageSquareIcon",[["path",{d:"M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z",key:"1lielz"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const De=n("MoonIcon",[["path",{d:"M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z",key:"a7tn18"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Le=n("PackageIcon",[["path",{d:"m7.5 4.27 9 5.15",key:"1c824w"}],["path",{d:"M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z",key:"hh9hay"}],["path",{d:"m3.3 7 8.7 5 8.7-5",key:"g66t2b"}],["path",{d:"M12 22V12",key:"d0xqtd"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Te=n("PanelLeftCloseIcon",[["rect",{width:"18",height:"18",x:"3",y:"3",rx:"2",key:"afitv7"}],["path",{d:"M9 3v18",key:"fh3hqa"}],["path",{d:"m16 15-3-3 3-3",key:"14y99z"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Ue=n("PanelLeftOpenIcon",[["rect",{width:"18",height:"18",x:"3",y:"3",rx:"2",key:"afitv7"}],["path",{d:"M9 3v18",key:"fh3hqa"}],["path",{d:"m14 9 3 3-3 3",key:"8010ee"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Ee=n("SettingsIcon",[["path",{d:"M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",key:"1qme2f"}],["circle",{cx:"12",cy:"12",r:"3",key:"1v7zrd"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Pe=n("ShoppingCartIcon",[["circle",{cx:"8",cy:"21",r:"1",key:"jimo8o"}],["circle",{cx:"19",cy:"21",r:"1",key:"13723u"}],["path",{d:"M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12",key:"9zh506"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const E=n("StoreIcon",[["path",{d:"m2 7 4.41-4.41A2 2 0 0 1 7.83 2h8.34a2 2 0 0 1 1.42.59L22 7",key:"ztvudi"}],["path",{d:"M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8",key:"1b2hhj"}],["path",{d:"M15 22v-4a2 2 0 0 0-2-2h-2a2 2 0 0 0-2 2v4",key:"2ebpfo"}],["path",{d:"M2 7h20",key:"1fcdvo"}],["path",{d:"M22 7v3a2 2 0 0 1-2 2v0a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 16 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 12 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 8 12a2.7 2.7 0 0 1-1.59-.63.7.7 0 0 0-.82 0A2.7 2.7 0 0 1 4 12v0a2 2 0 0 1-2-2V7",key:"jon5kx"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Fe=n("SunIcon",[["circle",{cx:"12",cy:"12",r:"4",key:"4exip2"}],["path",{d:"M12 2v2",key:"tus03m"}],["path",{d:"M12 20v2",key:"1lh1kg"}],["path",{d:"m4.93 4.93 1.41 1.41",key:"149t6j"}],["path",{d:"m17.66 17.66 1.41 1.41",key:"ptbguv"}],["path",{d:"M2 12h2",key:"1t8f8n"}],["path",{d:"M20 12h2",key:"1q8mjw"}],["path",{d:"m6.34 17.66-1.41 1.41",key:"1m8zz5"}],["path",{d:"m19.07 4.93-1.41 1.41",key:"1shlcs"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Ne=n("TruckIcon",[["path",{d:"M14 18V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v11a1 1 0 0 0 1 1h2",key:"wrbu53"}],["path",{d:"M15 18H9",key:"1lyqi6"}],["path",{d:"M19 18h2a1 1 0 0 0 1-1v-3.65a1 1 0 0 0-.22-.624l-3.48-4.35A1 1 0 0 0 17.52 8H14",key:"lysw3i"}],["circle",{cx:"17",cy:"18",r:"2",key:"332jqn"}],["circle",{cx:"7",cy:"18",r:"2",key:"19iecd"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Oe=n("UserCogIcon",[["circle",{cx:"18",cy:"15",r:"3",key:"gjjjvw"}],["circle",{cx:"9",cy:"7",r:"4",key:"nufk8"}],["path",{d:"M10 15H6a4 4 0 0 0-4 4v2",key:"1nfge6"}],["path",{d:"m21.7 16.4-.9-.3",key:"12j9ji"}],["path",{d:"m15.2 13.9-.9-.3",key:"1fdjdi"}],["path",{d:"m16.6 18.7.3-.9",key:"heedtr"}],["path",{d:"m19.1 12.2.3-.9",key:"1af3ki"}],["path",{d:"m19.6 18.7-.4-1",key:"1x9vze"}],["path",{d:"m16.8 12.3-.4-1",key:"vqeiwj"}],["path",{d:"m14.3 16.6 1-.4",key:"1qlj63"}],["path",{d:"m20.7 13.8 1-.4",key:"1v5t8k"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Y=n("UsersIcon",[["path",{d:"M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2",key:"1yyitq"}],["circle",{cx:"9",cy:"7",r:"4",key:"nufk8"}],["path",{d:"M22 21v-2a4 4 0 0 0-3-3.87",key:"kshegd"}],["path",{d:"M16 3.13a4 4 0 0 1 0 7.75",key:"1da9ce"}]]);/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const Ze=n("WalletIcon",[["path",{d:"M21 12V7H5a2 2 0 0 1 0-4h14v4",key:"195gfw"}],["path",{d:"M3 5v14a2 2 0 0 0 2 2h16v-5",key:"195n9w"}],["path",{d:"M18 12a2 2 0 0 0 0 4h4v-4Z",key:"vllfpd"}]]),Re=J("theme",()=>{const e=M(!1),t=()=>{e.value=!e.value},i=()=>{document.documentElement.classList.toggle("dark",e.value)};return A(e,i,{immediate:!0}),{isDark:e,toggle:t,apply:i}},{persist:!0}),We=J("app",()=>{const e=M(!1);return{sidebarCollapsed:e,toggleSidebar:()=>{e.value=!e.value}}},{persist:!0}),Ge={class:"flex min-h-screen bg-bg-secondary"},Ye={class:"flex h-16 items-center gap-2.5 border-b border-border px-4"},Je=["src","alt"],Ke={key:1,class:"flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary"},Qe={key:2,class:"truncate text-base font-bold text-text"},Xe={key:0,class:"border-b border-border px-4 py-2"},et={class:"flex items-center gap-1.5"},tt={class:"truncate text-xs text-text-muted"},at={class:"flex-1 overflow-y-auto px-2 py-3",role:"navigation","aria-label":"主导航"},st={key:0,class:"truncate"},ot={class:"border-t border-border p-2"},rt=["aria-label","aria-expanded"],nt={key:0},lt={class:"flex h-full flex-col bg-card"},it={class:"flex h-16 items-center gap-2.5 border-b border-border px-4"},dt=["src","alt"],ct={key:1,class:"flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary"},ht={class:"truncate text-base font-bold text-text"},pt={key:0,class:"border-b border-border px-4 py-2"},ut={class:"flex items-center gap-1.5"},yt={class:"truncate text-xs text-text-muted"},mt={class:"flex-1 overflow-y-auto px-2 py-3",role:"navigation","aria-label":"主导航"},xt={class:"truncate"},kt={class:"flex flex-1 flex-col overflow-hidden"},vt={class:"flex h-16 shrink-0 items-center justify-between border-b border-border bg-card px-4 lg:px-6"},gt={class:"flex items-center gap-3"},ft={class:"flex items-center gap-1.5 text-sm"},bt={class:"font-medium text-text"},_t={class:"flex items-center gap-1"},wt=["aria-label"],Mt={class:"relative rounded-lg p-2 hover:bg-bg-tertiary",title:"系统通知","aria-label":"通知"},Ct={key:0,class:"absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-semibold text-white ring-2 ring-card","aria-live":"polite"},It={key:1,class:"absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-danger ring-2 ring-card","aria-live":"polite"},St={class:"max-h-96 overflow-y-auto"},jt={class:"mb-2 flex items-center justify-between border-b border-border-light pb-2"},zt={key:0,class:"text-xs text-danger"},$t={key:0,class:"py-8 text-center text-sm text-text-muted"},qt={key:1,class:"space-y-2"},At={class:"flex items-start justify-between gap-2"},Ht={class:"text-sm font-medium text-text"},Vt={class:"mt-1 line-clamp-2 text-xs text-text-secondary"},Bt={class:"mt-1 text-xs text-text-muted"},Dt={class:"mt-3 border-t border-border-light pt-2 text-center"},Lt={class:"flex cursor-pointer items-center gap-2 rounded-lg p-1.5 hover:bg-bg-tertiary","aria-label":"用户菜单",role:"button",tabindex:"0"},Tt={class:"flex h-8 w-8 items-center justify-center rounded-full bg-primary text-xs font-semibold text-white"},Ut={class:"hidden sm:block"},Et={class:"text-sm font-medium text-text"},Pt={class:"text-xs text-text-muted"},Ft={class:"flex items-center gap-2"},Nt={class:"flex-1 overflow-y-auto"},ta=K({__name:"Layout",setup(e){const t=he(),i=pe(),v=ae(),H=Re(),F=We(),C=M(!1),Q=[{path:"/dashboard",name:"数据总览",icon:qe,roles:[1,2,4,5,6]},{path:"/dish",name:"菜品管理",icon:we,roles:[1,2,5,6]},{path:"/menu",name:"菜单管理",icon:fe,roles:[1,2,5,6]},{path:"/order",name:"订单管理",icon:Ie,roles:[1,2,6]},{path:"/order-summary",name:"订餐汇总",icon:ze,roles:[1,2,5,6]},{path:"/employee",name:"员工管理",icon:Y,roles:[1,2,6]},{path:"/department",name:"部门管理",icon:be,roles:[1,2,6]},{path:"/timer",name:"就餐时段",icon:Se,roles:[1,2,6]},{path:"/notification",name:"通知管理",icon:He,roles:[1,2,6]},{path:"/supplier",name:"供应商管理",icon:Ne,roles:[1,2,5,6]},{path:"/purchase",name:"采购管理",icon:Pe,roles:[1,2,5,6]},{path:"/material",name:"库存管理",icon:Le,roles:[1,2,5,6]},{path:"/feedback",name:"反馈评价",icon:Be,roles:[1,2,6]},{path:"/group-order",name:"团体订餐",icon:Y,roles:[1,2,6]},{path:"/report",name:"报表统计",icon:ve,roles:[1,2,4,6]},{path:"/daily-close",name:"日终对账",icon:Ce,roles:[1,2,4,6]},{path:"/settlement",name:"关店对账",icon:_e,roles:[1,2,4,6]},{path:"/recharge",name:"充值记录",icon:Ze,roles:[1,2,4,6]},{path:"/store",name:"食堂管理",icon:E,roles:[1]},{path:"/admin",name:"账号管理",icon:Oe,roles:[1,2]},{path:"/settings",name:"系统设置",icon:Ee,roles:[1]},{path:"/backup",name:"备份恢复",icon:je,roles:[1,2]},{path:"/operation-log",name:"操作日志",icon:$e,roles:[1,2]}],x=$(()=>F.sidebarCollapsed),N=$(()=>Q.filter(c=>!c.roles||v.hasRole(...c.roles))),X=$(()=>i.meta.title||"数据总览"),S=c=>i.path===c,m=M(null),O=async()=>{const c=v.storeId;if(!c||c===0){m.value=null;return}try{m.value=await ke.getCurrent()}catch{m.value=null}};A(()=>v.storeId,O);const ee=async c=>{c==="logout"&&(await v.logout(),ue.success("已退出登录"),t.push("/login"))};A(()=>i.path,()=>{C.value=!1});const _=M(0),V=M([]),Z=$(()=>[...V.value].sort((c,o)=>(o.createdAt||"").localeCompare(c.createdAt||"")).slice(0,5)),te=c=>c?c.replace("T"," ").slice(0,16):"",B=async()=>{const c=v.storeId;if(!c){_.value=0;return}try{const o=await xe.list({storeId:c});V.value=me(o),_.value=V.value.filter(f=>f.status===1&&f.displayStatus==="active").length}catch{_.value=0}};A(()=>i.path,()=>{B()});let j=null;return se(()=>{O(),B(),j=setInterval(B,6e4)}),oe(()=>{j&&clearInterval(j),j=null}),(c,o)=>{var R,W;const f=ie("router-link");return s(),d("div",Ge,[a("aside",{class:z(["hidden lg:flex flex-col border-r border-border bg-card transition-all duration-300",x.value?"w-16":"w-60"])},[a("div",Ye,[(R=m.value)!=null&&R.logoUrl?(s(),d("img",{key:0,src:m.value.logoUrl,alt:m.value.name,class:"h-9 w-9 shrink-0 rounded-xl object-cover"},null,8,Je)):(s(),d("div",Ke,[h(r(G),{class:"h-5 w-5 text-white"})])),x.value?g("",!0):(s(),d("span",Qe,u(((W=m.value)==null?void 0:W.name)||"企业智慧食堂"),1))]),!x.value&&r(v).isSuperAdmin?(s(),d("div",Xe,[a("div",et,[h(r(E),{class:"h-3.5 w-3.5 shrink-0 text-text-muted"}),a("span",tt,u(m.value?m.value.name:"全局视图"),1),h(f,{to:"/store",class:"ml-auto shrink-0 text-xs text-primary hover:underline"},{default:p(()=>[...o[5]||(o[5]=[w(" 切换 ",-1)])]),_:1})])])):g("",!0),a("nav",at,[(s(!0),d(L,null,T(N.value,l=>(s(),k(f,{key:l.path,to:l.path,title:x.value?l.name:"","aria-current":S(l.path)?"page":void 0,class:z(["group mb-1 flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-all",[S(l.path)?"bg-primary-50 font-medium text-primary":"text-text-secondary hover:bg-bg-tertiary hover:text-text",x.value?"justify-center":""]])},{default:p(()=>[(s(),k(U(l.icon),{class:"h-5 w-5 shrink-0"})),x.value?g("",!0):(s(),d("span",st,u(l.name),1))]),_:2},1032,["to","title","aria-current","class"]))),128))]),a("div",ot,[a("button",{class:z(["flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-sm text-text-secondary transition-colors hover:bg-bg-tertiary",x.value?"justify-center":""]),"aria-label":x.value?"展开菜单":"折叠菜单","aria-expanded":!x.value,onClick:o[0]||(o[0]=l=>r(F).toggleSidebar())},[(s(),k(U(x.value?r(Ue):r(Te)),{class:"h-5 w-5 shrink-0"})),x.value?g("",!0):(s(),d("span",nt,"收起菜单"))],10,rt)])],2),h(r(re),{modelValue:C.value,"onUpdate:modelValue":o[2]||(o[2]=l=>C.value=l),direction:"ltr",size:"260px","with-header":!1},{default:p(()=>{var l,I;return[a("div",lt,[a("div",it,[(l=m.value)!=null&&l.logoUrl?(s(),d("img",{key:0,src:m.value.logoUrl,alt:m.value.name,class:"h-9 w-9 shrink-0 rounded-xl object-cover"},null,8,dt)):(s(),d("div",ct,[h(r(G),{class:"h-5 w-5 text-white"})])),a("span",ht,u(((I=m.value)==null?void 0:I.name)||"企业智慧食堂"),1)]),r(v).isSuperAdmin?(s(),d("div",pt,[a("div",ut,[h(r(E),{class:"h-3.5 w-3.5 shrink-0 text-text-muted"}),a("span",yt,u(m.value?m.value.name:"全局视图"),1),h(f,{to:"/store",class:"ml-auto shrink-0 text-xs text-primary hover:underline"},{default:p(()=>[...o[6]||(o[6]=[w(" 切换 ",-1)])]),_:1})])])):g("",!0),a("nav",mt,[(s(!0),d(L,null,T(N.value,b=>(s(),k(f,{key:b.path,to:b.path,"aria-current":S(b.path)?"page":void 0,class:z(["mb-1 flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-all",S(b.path)?"bg-primary-50 font-medium text-primary":"text-text-secondary hover:bg-bg-tertiary"]),onClick:o[1]||(o[1]=D=>C.value=!1)},{default:p(()=>[(s(),k(U(b.icon),{class:"h-5 w-5 shrink-0"})),a("span",xt,u(b.name),1)]),_:2},1032,["to","aria-current","class"]))),128))])])]}),_:1},8,["modelValue"]),a("div",kt,[a("header",vt,[a("div",gt,[a("button",{class:"rounded-lg p-2 hover:bg-bg-tertiary lg:hidden","aria-label":"打开菜单",onClick:o[3]||(o[3]=l=>C.value=!0)},[h(r(Ve),{class:"h-5 w-5 text-text-secondary"})]),a("div",ft,[h(f,{to:"/dashboard",class:"flex items-center text-text-muted hover:text-primary"},{default:p(()=>[h(r(ye),{class:"h-4 w-4"})]),_:1}),h(r(Me),{class:"h-4 w-4 text-text-muted"}),a("span",bt,u(X.value),1)])]),a("div",_t,[a("button",{class:"rounded-lg p-2 hover:bg-bg-tertiary","aria-label":r(H).isDark?"切换到亮色主题":"切换到暗色主题",onClick:o[4]||(o[4]=l=>r(H).toggle())},[r(H).isDark?(s(),k(r(Fe),{key:0,class:"h-5 w-5 text-warning"})):(s(),k(r(De),{key:1,class:"h-5 w-5 text-text-secondary"}))],8,wt),h(r(ne),{placement:"bottom-end",width:380,trigger:"click","popper-class":"notification-popover"},{reference:p(()=>[a("button",Mt,[h(r(ge),{class:"h-5 w-5 text-text-secondary"}),_.value>0?(s(),d("span",Ct,u(_.value>99?"99+":_.value),1)):(s(),d("span",It))])]),default:p(()=>[a("div",St,[a("div",jt,[o[7]||(o[7]=a("span",{class:"text-sm font-semibold text-text"},"系统通知",-1)),_.value>0?(s(),d("span",zt,u(_.value)+" 条进行中",1)):g("",!0)]),Z.value.length===0?(s(),d("div",$t," 暂无通知 ")):(s(),d("div",qt,[(s(!0),d(L,null,T(Z.value,l=>(s(),d("div",{key:l.id,class:"rounded-lg border border-border-light p-3 transition-colors hover:bg-bg-tertiary"},[a("div",At,[a("span",Ht,u(l.title),1),l.displayStatus==="active"?(s(),k(r(q),{key:0,type:"success",size:"small"},{default:p(()=>[...o[8]||(o[8]=[w("进行中",-1)])]),_:1})):l.displayStatus==="pending"?(s(),k(r(q),{key:1,type:"warning",size:"small"},{default:p(()=>[...o[9]||(o[9]=[w("待上架",-1)])]),_:1})):l.displayStatus==="expired"?(s(),k(r(q),{key:2,type:"info",size:"small"},{default:p(()=>[...o[10]||(o[10]=[w("已过期",-1)])]),_:1})):(s(),k(r(q),{key:3,type:"info",size:"small"},{default:p(()=>[...o[11]||(o[11]=[w("已下架",-1)])]),_:1}))]),a("p",Vt,u(l.content),1),a("div",Bt,u(te(l.createdAt)),1)]))),128))])),a("div",Dt,[h(f,{to:"/notification",class:"text-xs text-primary hover:underline"},{default:p(()=>[...o[12]||(o[12]=[w(" 查看全部通知 → ",-1)])]),_:1})])])]),_:1}),h(r(le),{trigger:"click",onCommand:ee},{dropdown:p(()=>[h(r(de),null,{default:p(()=>[h(r(ce),{command:"logout"},{default:p(()=>[a("span",Ft,[h(r(Ae),{class:"h-4 w-4"}),o[13]||(o[13]=w(" 退出登录 ",-1))])]),_:1})]),_:1})]),default:p(()=>{var l,I,b,D;return[a("div",Lt,[a("div",Tt,u(((I=(l=r(v).admin)==null?void 0:l.name)==null?void 0:I.charAt(0))||"管"),1),a("div",Ut,[a("div",Et,u(((b=r(v).admin)==null?void 0:b.name)||"管理员"),1),a("div",Pt,u((D=r(v).admin)==null?void 0:D.username),1)])])]}),_:1})])]),a("main",Nt,[P(c.$slots,"default")])])])}}}),Ot=["aria-label"],Zt={key:0,class:"mb-6 flex flex-wrap items-center justify-between gap-4"},Rt={class:"min-w-0"},Wt={key:0,class:"text-2xl font-bold tracking-tight text-text"},Gt={key:1,class:"mt-1.5 text-sm text-text-secondary"},Yt={key:0,class:"flex shrink-0 flex-wrap items-center gap-3"},aa=K({__name:"PageContainer",props:{title:{},description:{}},setup(e){return(t,i)=>(s(),d("div",{class:"mx-auto w-full max-w-[1600px] px-4 py-6 sm:px-6 lg:px-8",role:"main","aria-label":e.title},[e.title||e.description||t.$slots.actions?(s(),d("header",Zt,[a("div",Rt,[e.title?(s(),d("h1",Wt,u(e.title),1)):g("",!0),e.description?(s(),d("p",Gt,u(e.description),1)):g("",!0)]),t.$slots.actions?(s(),d("div",Yt,[P(t.$slots,"actions")])):g("",!0)])):g("",!0),P(t.$slots,"default")],8,Ot))}});export{ve as B,Se as C,je as D,ze as F,De as M,Le as P,Fe as S,Ne as T,Y as U,Ze as W,ta as _,aa as a,we as b,Ie as c,Me as d,Ee as e,be as f,xe as g,He as h,Pe as i,Ce as j,_e as k,E as l,ge as m,me as n,ea as o,Be as p,ke as s,Re as u};
