import{c as i}from"./createLucideIcon-C2wt7JDe.js";import{u as n,q as r,z as a}from"./index-B545ye7o.js";import{s as u}from"./PageContainer.vue_vue_type_script_setup_true_lang-3m2iFBTp.js";/**
 * @license lucide-vue-next v0.323.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const p=i("DollarSignIcon",[["line",{x1:"12",x2:"12",y1:"2",y2:"22",key:"7eqyqh"}],["path",{d:"M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6",key:"1b0p4s"}]]),m=()=>{const o=n(),s=a(()=>o.isSuperAdmin),e=r([]),t=r(o.storeId||void 0),c=a(()=>t.value||o.storeId||null);return{isSuperAdmin:s,stores:e,selectedStoreId:t,activeStoreId:c,fetchStores:async()=>{if(s.value)try{e.value=await u.list(),!t.value&&e.value.length&&(t.value=e.value[0].id)}catch{}}}};export{p as D,m as u};
