import{j as e}from"./utils-BLj8xSjE.js";import{b as l}from"./router-Ba2vbdpG.js";import{a as H,c as le,i as de}from"./api-CZsjvv7R.js";import{M as Ee}from"./charts-DNQUTm-z.js";import{A as Ke}from"./ai-report-CpSTkLtk.js";import{n as Ue,z as Be,X as z,aI as J,d as m,I as Se,u as ce,B as G,aT as E,aV as qe,ai as ue,f as me,ae as je,r as K,ad as Pe,E as _,s as L,m as Oe,K as j,N as r,av as _e,Z as ye,aZ as Ce,x as Te,w as V,G as ke,o as D,v as We,aL as Re,S as fe,ah as we,g as Me,k as Ve,aj as h,aM as ze,a5 as De,ar as Ye}from"./antd-B6p9hJ6P.js";import{u as Ae}from"./i18n-DpFts_lT.js";const Xe=({visible:p,onClose:b,language:B})=>{const[I,o]=l.useState([]),[y,k]=l.useState(""),[Y,f]=l.useState([]),[x,i]=l.useState(""),[R,N]=l.useState(""),[W,U]=l.useState(""),[ve,Z]=l.useState(!1),[q,s]=l.useState(!1),[X,P]=l.useState(!1),[Q,A]=l.useState(""),[$,F]=l.useState([null,null]),{t:d}=Ae();l.useEffect(()=>{p&&xe()},[p]),l.useEffect(()=>{y&&(ee(y),S(y))},[y]);const xe=async()=>{try{const a=await H.get("/api/copilot/providers");Array.isArray(a.data)&&o(a.data)}catch(a){console.error("Failed to load providers:",a)}},ee=async a=>{try{const T=await H.get(`/api/copilot/providers/${a}/models`);Array.isArray(T.data)&&f(T.data)}catch(T){console.error("Failed to load models:",T)}},S=a=>{const T=I.find(w=>w.provider===a);T&&(i(T.model||""),s(T.isValid||!1))},te=async()=>{if(!R){L.warning(d("ai.please_input_api_key")||"请输入API Key");return}Z(!0);try{(await H.post("/api/copilot/validate",{provider:y,apiKey:R,baseUrl:W||null})).data.valid?(s(!0),L.success(d("ai.validate_success")||"API Key验证成功")):(s(!1),L.error(d("ai.validate_failed")||"API Key验证失败"))}catch(a){s(!1);const T=a.response?.data?.message||a.message||d("ai.validate_failed")||"API Key验证失败";L.error(T),console.error("Validation error:",a)}finally{Z(!1)}},C=async()=>{if(!x){L.warning(d("ai.please_select_model")||"请选择模型");return}if(!q){L.warning(d("ai.please_validate_first")||"请先验证API Key");return}if(!$[0]||!$[1]){L.warning(d("ai.please_select_time_range")||"请选择时间段");return}try{await H.post("/api/copilot/config",{provider:y,model:x,apiKey:R,baseUrl:W||null})}catch(a){console.error("Failed to save config:",a)}P(!0),A("");try{const a=await H.post("/api/ai/analyze/timerange",{provider:y,startTime:$[0].valueOf(),endTime:$[1].valueOf(),language:B});if(!a)return;a.data.code===200?A(a.data.data?.result||""):L.error(a.data.message||d("ai.analysis_failed")||"AI分析失败")}catch(a){L.error(a.message||d("ai.analysis_failed")||"AI分析失败")}finally{P(!1)}},se=I.filter(a=>a.region==="DOMESTIC"),oe=I.filter(a=>a.region==="OVERSEAS"),re=a=>e.jsx(_,{hoverable:!0,size:"small",style:{marginBottom:8,borderColor:y===a.provider?"#1890ff":void 0,backgroundColor:y===a.provider?"#e6f7ff":void 0},onClick:()=>{k(a.provider),A("")},children:e.jsxs(m,{children:[e.jsx(K,{color:a.isValid?"success":"default",children:a.providerName}),a.isValid&&e.jsx(ce,{style:{color:"#52c41a"}})]})},a.provider);return e.jsxs(Ue,{title:e.jsxs(m,{children:[e.jsx(Pe,{}),d("ai.ai_analysis")||"AI智能分析"]}),open:p,onCancel:b,footer:null,width:800,destroyOnClose:!0,children:[!Q&&!X?e.jsxs(e.Fragment,{children:[e.jsx(Be,{items:[{key:"domestic",label:d("ai.domestic_models")||"国内大模型",children:e.jsx("div",{style:{maxHeight:200,overflow:"auto"},children:se.map(re)})},{key:"overseas",label:d("ai.overseas_models")||"国外大模型",children:e.jsx("div",{style:{maxHeight:200,overflow:"auto"},children:oe.map(re)})}]}),y&&e.jsxs("div",{style:{marginTop:16},children:[e.jsx(z,{children:d("ai.config")||"配置"}),e.jsxs("div",{style:{marginBottom:12},children:[e.jsx("label",{style:{display:"block",marginBottom:8},children:d("ai.select_model")||"选择模型"}),e.jsx(J.Group,{value:x,onChange:a=>i(a.target.value),style:{width:"100%"},children:e.jsx(m,{direction:"vertical",style:{width:"100%"},children:Y.map(a=>e.jsx(J,{value:a,style:{color:"#e2e8f0"},children:a},a))})})]}),e.jsxs("div",{style:{marginBottom:12},children:[e.jsx("label",{style:{display:"block",marginBottom:4},children:"API Key"}),e.jsx(Se.Password,{value:R,onChange:a=>{N(a.target.value),s(!1)},placeholder:d("ai.api_key_placeholder")||"请输入API Key",autoComplete:"new-password","data-lpignore":"true",suffix:q?e.jsx(ce,{style:{color:"#52c41a"}}):e.jsx(G,{type:"link",size:"small",loading:ve,onClick:te,children:d("ai.validate")||"验证"})})]}),e.jsxs("div",{style:{marginBottom:12},children:[e.jsx("label",{style:{display:"block",marginBottom:4},children:d("ai.base_url")||"API地址（可选）"}),e.jsx(Se,{value:W,onChange:a=>U(a.target.value),placeholder:d("ai.base_url_placeholder")||"留空使用默认地址",autoComplete:"off","data-lpignore":"true"})]}),e.jsx(z,{children:d("ai.timerange_analysis")||"时间段分析"}),e.jsxs("div",{style:{marginBottom:12},children:[e.jsx("label",{style:{display:"block",marginBottom:8},children:d("ai.select_time_range")||"选择时间段"}),e.jsxs(m,{direction:"vertical",style:{width:"100%"},size:"small",children:[e.jsxs(m,{wrap:!0,children:[e.jsx(G,{size:"small",onClick:()=>F([E().subtract(10,"minute"),E()]),children:d("ai.last_10min")||"最近10分钟"}),e.jsx(G,{size:"small",onClick:()=>F([E().subtract(30,"minute"),E()]),children:d("ai.last_30min")||"最近30分钟"}),e.jsx(G,{size:"small",onClick:()=>F([E().subtract(1,"hour"),E()]),children:d("ai.last_1hour")||"最近1小时"}),e.jsx(G,{size:"small",onClick:()=>F([E().subtract(6,"hour"),E()]),children:d("ai.last_6hours")||"最近6小时"})]}),e.jsx(qe.RangePicker,{showTime:!0,value:$,onChange:a=>F(a),style:{width:"100%"},placeholder:[d("ai.start_time")||"开始时间",d("ai.end_time")||"结束时间"]})]})]}),e.jsx(G,{type:"primary",icon:e.jsx(ue,{}),loading:X,disabled:!q||!x||!$[0]||!$[1],onClick:C,block:!0,children:q?x?d("ai.start_analysis")||"开始分析":d("ai.please_select_model")||"请选择模型":d("ai.please_validate_first")||"请先验证API Key"})]})]}):X?e.jsxs("div",{style:{textAlign:"center",padding:"60px 0"},children:[e.jsx(me,{size:"large"}),e.jsx("div",{style:{marginTop:16,color:"#666"},children:d("ai.analyzing")||"AI分析中，请稍候..."})]}):e.jsxs("div",{className:"ai-analysis-result",children:[e.jsx("div",{style:{marginBottom:16,display:"flex",justifyContent:"space-between",alignItems:"center"},children:e.jsxs(m,{children:[e.jsx(G,{onClick:()=>A(""),icon:e.jsx(je,{}),children:d("ai.reanalyze")||"重新分析"}),e.jsx(K,{color:"blue",icon:e.jsx(ce,{}),children:"Analysis Complete"})]})}),e.jsx("div",{className:"ai-result-container",children:e.jsx("div",{className:"ai-result-content",children:e.jsx(Ke,{content:Q})})})]}),e.jsx("style",{children:`
        /* 防止 LastPass 等密码管理器注入的元素干扰表单 */
        [data-lastpass-icon-root] {
          display: none !important;
          visibility: hidden !important;
          pointer-events: none !important;
        }
        [data-lastpass-icon-root] * {
          display: none !important;
        }

        .ai-analysis-result {
          display: flex;
          flex-direction: column;
        }

        /* 结果容器 - 添加更好的背景和边框 */
        .ai-result-container {
          background: linear-gradient(135deg, #0f172a 0%, #1a2338 100%);
          border: 1px solid rgba(59, 130, 246, 0.2);
          border-radius: 12px;
          padding: 24px;
          max-height: 500px;
          overflow: auto;
          box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
        }

        .ai-result-content {
          font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
          line-height: 1.8;
        }

        /* 主标题 - 添加醒目的卡片样式 */
        .ai-result-content h1 {
          font-size: 20px;
          font-weight: 700;
          color: #f1f5f9;
          background: linear-gradient(90deg, rgba(59, 130, 246, 0.15) 0%, transparent 100%);
          border-left: 4px solid #3b82f6;
          padding: 12px 16px;
          margin: 0 0 20px 0;
          border-radius: 0 8px 8px 0;
        }

        /* 二级标题 - 添加明显的分隔背景 */
        .ai-result-content h2 {
          font-size: 17px;
          font-weight: 600;
          color: #e2e8f0;
          background: rgba(16, 185, 129, 0.1);
          border-left: 3px solid #10b981;
          padding: 10px 14px;
          margin: 24px 0 16px 0;
          border-radius: 0 6px 6px 0;
        }

        /* 三级标题 */
        .ai-result-content h3 {
          font-size: 15px;
          font-weight: 500;
          color: #94a3b8;
          border-bottom: 1px dashed rgba(148, 163, 184, 0.3);
          padding-bottom: 6px;
          margin: 16px 0 12px 0;
        }

        /* 段落 */
        .ai-result-content p {
          color: #cbd5e1;
          margin-bottom: 16px;
          line-height: 1.8;
          padding: 0 4px;
        }

        /* 无序列表 - 添加彩色圆点和卡片效果 */
        .ai-result-content ul {
          list-style: none;
          padding-left: 0;
          margin-bottom: 20px;
        }

        .ai-result-content ul li {
          position: relative;
          padding: 10px 12px 10px 24px;
          margin-bottom: 8px;
          background: rgba(30, 41, 59, 0.5);
          border-radius: 6px;
          border: 1px solid rgba(71, 85, 105, 0.3);
          color: #e2e8f0;
          line-height: 1.7;
          transition: background 0.2s ease;
        }

        .ai-result-content ul li:hover {
          background: rgba(30, 41, 59, 0.8);
        }

        .ai-result-content ul li::before {
          content: '';
          position: absolute;
          left: 8px;
          top: 14px;
          width: 8px;
          height: 8px;
          background: #3b82f6;
          border-radius: 50%;
          box-shadow: 0 0 6px rgba(59, 130, 246, 0.5);
        }

        /* 有序列表 - 添加编号和卡片效果 */
        .ai-result-content ol {
          list-style: none;
          padding-left: 0;
          margin-bottom: 20px;
          counter-reset: item;
        }

        .ai-result-content ol li {
          position: relative;
          counter-increment: item;
          padding: 10px 12px 10px 36px;
          margin-bottom: 8px;
          background: rgba(30, 41, 59, 0.5);
          border-radius: 6px;
          border: 1px solid rgba(71, 85, 105, 0.3);
          color: #e2e8f0;
          line-height: 1.7;
        }

        .ai-result-content ol li::before {
          content: counter(item);
          position: absolute;
          left: 10px;
          top: 10px;
          width: 20px;
          height: 20px;
          background: linear-gradient(135deg, #3b82f6, #8b5cf6);
          border-radius: 50%;
          color: white;
          font-size: 11px;
          font-weight: 600;
          display: flex;
          align-items: center;
          justify-content: center;
          text-align: center;
          line-height: 20px;
        }

        /* 代码块 */
        .ai-result-content code {
          background: #020617;
          padding: 4px 10px;
          border-radius: 6px;
          font-family: 'JetBrains Mono', 'SF Mono', 'Monaco', 'Consolas', monospace;
          font-size: 13px;
          color: #a5f3fc;
          border: 1px solid rgba(71, 85, 105, 0.4);
        }

        .ai-result-content pre {
          background: #020617;
          padding: 16px;
          border-radius: 8px;
          overflow: auto;
          margin: 16px 0;
          border: 1px solid rgba(59, 130, 246, 0.2);
          box-shadow: inset 0 2px 8px rgba(0, 0, 0, 0.3);
        }

        .ai-result-content pre code {
          background: transparent;
          padding: 0;
          border: none;
          color: #e2e8f0;
        }

        /* 引用块 */
        .ai-result-content blockquote {
          border-left: 3px solid #f59e0b;
          padding: 12px 16px;
          margin: 16px 0;
          color: #94a3b8;
          background: rgba(245, 158, 11, 0.08);
          border-radius: 0 6px 6px 0;
          font-style: italic;
        }

        /* 强调文字 */
        .ai-result-content strong {
          font-weight: 600;
          color: #f1f5f9;
          background: rgba(59, 130, 246, 0.1);
          padding: 2px 6px;
          border-radius: 4px;
        }

        /* 表格 */
        .ai-result-content table {
          width: 100%;
          border-collapse: collapse;
          margin: 16px 0;
          background: #1a2338;
          border-radius: 8px;
          overflow: hidden;
          border: 1px solid rgba(71, 85, 105, 0.3);
        }

        .ai-result-content th {
          background: linear-gradient(90deg, #0f172a, #1e293b);
          padding: 12px 16px;
          text-align: left;
          font-weight: 600;
          color: #f1f5f9;
          border-bottom: 2px solid #3b82f6;
        }

        .ai-result-content td {
          padding: 12px 16px;
          color: #cbd5e1;
          border-bottom: 1px solid rgba(71, 85, 105, 0.2);
        }

        .ai-result-content tr:last-child td {
          border-bottom: none;
        }

        .ai-result-content tr:hover td {
          background: rgba(30, 41, 59, 0.5);
        }

        /* 分隔线 */
        .ai-result-content hr {
          border: none;
          height: 1px;
          background: linear-gradient(90deg, transparent, rgba(59, 130, 246, 0.3), transparent);
          margin: 24px 0;
        }
      `})]})},{Text:n,Title:Je}=Oe,Ie={blue:{main:"#1890ff",gradient:["#1890ff","#69c0ff","#e6f7ff"]},green:{main:"#52c41a",gradient:["#52c41a","#95de64","#f6ffed"]},purple:{main:"#722ed1",gradient:["#722ed1","#b37feb","#f9f0ff"]},orange:{main:"#fa8c16",gradient:["#fa8c16","#ffc069","#fff7e6"]},magenta:{main:"#eb2f96",gradient:["#eb2f96","#ff85c0","#fff0f6"]},cyan:{main:"#13c2c2",gradient:["#13c2c2","#5cdbd3","#e6fffb"]},red:{main:"#ff4d4f",gradient:["#ff4d4f","#ff7875","#fff1f0"]}},v=({title:p,data:b,colorKey:B,unit:I,yAxisLabel:o="",valueTransform:y})=>{const k=Ie[B]||Ie.blue,Y=[{id:p,data:(b||[]).map(f=>({x:new Date(f.timestamp),y:y?y(f.value):f.value}))}];return!b||b.length===0?e.jsx(_,{styles:{body:{padding:"16px"}},children:e.jsx("div",{style:{height:200,display:"flex",alignItems:"center",justifyContent:"center"},children:e.jsx(n,{type:"secondary",children:"No data available"})})}):e.jsx(_,{styles:{body:{padding:"16px"}},title:e.jsx(n,{strong:!0,style:{fontSize:13},children:p}),children:e.jsx("div",{style:{height:180},children:e.jsx(Ee,{data:Y,margin:{top:10,right:20,bottom:40,left:60},xScale:{type:"time",format:"native"},yScale:{type:"linear",min:"auto",max:"auto",stacked:!1},curve:"monotoneX",axisTop:null,axisRight:null,axisBottom:{format:"%H:%M",tickValues:5,legend:"",legendOffset:36,legendPosition:"middle"},axisLeft:{tickSize:5,tickPadding:8,tickRotation:0,legend:o,legendOffset:-50,legendPosition:"middle",format:f=>{const x=Math.abs(f);return x>=1e6?(f/1e6).toFixed(1)+"M":x>=1e3?(f/1e3).toFixed(1)+"K":x>=1?f.toFixed(1):f.toFixed(2)}},enableGridX:!1,enableGridY:!0,gridYValues:4,colors:k.main,lineWidth:2,enablePoints:!1,enableCrosshair:!0,crosshairType:"bottom",pointSize:4,pointColor:{theme:"background"},pointBorderWidth:2,pointBorderColor:{from:"serieColor"},pointLabelYOffset:-12,useMesh:!0,legends:[],defs:[{id:`gradient-${B}`,type:"linearGradient",colors:[{offset:0,color:k.gradient[0]},{offset:50,color:k.gradient[1]},{offset:100,color:k.gradient[2]}]}],fill:[{match:"*",id:`gradient-${B}`}],enableArea:!0,areaOpacity:.3,animate:!0,motionConfig:"gentle",tooltip:({point:f})=>{const x=f.data.y,i=I.trim();let R;return Math.abs(x)>=1e6?R=(x/1e6).toFixed(1)+"M"+(i?" "+i:""):Math.abs(x)>=1e3?R=(x/1e3).toFixed(1)+"K"+(i?" "+i:""):Math.abs(x)>=1?R=x.toFixed(2)+(i?" "+i:""):R=x.toFixed(4)+(i?" "+i:""),e.jsxs("div",{style:{background:"#1e293b",padding:"8px 12px",border:"1px solid rgba(148, 163, 184, 0.3)",borderRadius:4,boxShadow:"0 4px 12px rgba(0, 0, 0, 0.5)",whiteSpace:"nowrap"},children:[e.jsx("div",{style:{fontSize:12,fontWeight:600,marginBottom:4,color:"#f1f5f9"},children:f.data.x.toLocaleTimeString()}),e.jsx("div",{style:{fontSize:13,color:k.main},children:R})]})}})})})},M=p=>{if(!p)return{value:0,unit:"B",display:"0 B"};const b=1024,B=["B","KB","MB","GB"],I=Math.min(Math.floor(Math.log(p)/Math.log(b)),B.length-1),o=parseFloat((p/Math.pow(b,I)).toFixed(2)),y=B[I];return{value:o,unit:y,display:`${o} ${y}`}},at=({instanceId:p,instanceDbId:b})=>{const[B,I]=l.useState(!1),[o,y]=l.useState(null),[k,Y]=l.useState(!1),[f,x]=l.useState(null),[i,R]=l.useState(null),[N,W]=l.useState(!1),[U,ve]=l.useState(1),[Z,q]=l.useState(!1),{t:s,i18n:X}=Ae(),[P,Q]=l.useState("realtime"),[A,$]=l.useState(1),[F,d]=l.useState([]),[xe,ee]=l.useState(!1),[S,te]=l.useState(null),[C,se]=l.useState(null),[oe,re]=l.useState([]),[a,T]=l.useState(!1),w=l.useRef(new Map),ae=l.useCallback(async()=>{if(!b)return;const t=`pods-${Date.now()}`,c=le();w.current.set(t,c);try{ee(!0);const u=await H.get(`/api/instances/${b}/pods`,{cancelToken:c.token});if(!u)return;if(u.data.code===200){const g=u.data.data||[];if(d(g),g.length>0&&!S){te(g[0]);const O=g[0].managementPort||9091;se(g[0].podIP?`${g[0].podIP}:${O}`:null)}}}catch(u){de(u)||console.error("Failed to load pods:",u)}finally{ee(!1),w.current.delete(t)}},[b,S]),ge=l.useCallback(async()=>{const t=`metrics-${Date.now()}`,c=le();w.current.set(t,c);try{I(!0),x(null);let u="";p&&(u=`?instanceId=${p}`),C&&(u=u?`${u}&podInstance=${C}`:`?podInstance=${C}`);const g=await H.get(`/api/monitor/metrics${u}`,{cancelToken:c.token});if(!g)return;g.data.code===200?(y(g.data.data),Y(g.data.prometheusAvailable)):x(g.data.message||"Failed to load metrics")}catch(u){de(u)||x(u.message||"Failed to connect to server")}finally{I(!1),w.current.delete(t)}},[p,C]),ne=l.useCallback(async(t=!1)=>{const c=`history-${Date.now()}`,u=le();w.current.set(c,u);try{t&&W(!0);let O=`hours=${P==="historical"?A:U}`;p&&(O=`${O}&instanceId=${p}`),C&&(O=`${O}&podInstance=${C}`);const pe=await H.get(`/api/monitor/history?${O}`,{cancelToken:u.token});if(!pe)return;pe.data.code===200&&R(pe.data.data)}catch(g){de(g)||console.error("Failed to load history:",g)}finally{W(!1),w.current.delete(c)}},[p,C,P,U,A]),he=l.useCallback(async()=>{const t=`routes-${Date.now()}`,c=le();w.current.set(t,c);try{T(!0);let u=`hours=${U}`;p&&(u=`${u}&instanceId=${p}`),C&&(u=`${u}&podInstance=${C}`);const g=await H.get(`/api/monitor/routes?${u}`,{cancelToken:c.token});if(!g)return;g.data.code===200&&re(g.data.data||[])}catch(u){de(u)||console.error("Failed to load route metrics:",u)}finally{T(!1),w.current.delete(t)}},[p,C,U]);l.useEffect(()=>{if(b){ae();const t=setInterval(ae,3e4);return()=>clearInterval(t)}},[b,ae]),l.useEffect(()=>{ge(),ne(!0),he();const t=P==="realtime"?setInterval(ge,1e4):null,c=setInterval(()=>ne(!1),3e4),u=setInterval(()=>he(),3e4);return()=>{t&&clearInterval(t),clearInterval(c),clearInterval(u),w.current.forEach(g=>g.cancel("Component unmounted")),w.current.clear()}},[p,C,P,U,A,ge,ne,he]);const $e=t=>{const c=F.find(u=>u.name===t);if(c){te(c);const u=c.managementPort||9091;se(c.podIP?`${c.podIP}:${u}`:null)}},Fe=t=>t==="UP"?"success":t==="DOWN"?"error":"warning",Le=t=>t==="UP"?e.jsx(ce,{style:{color:"#52c41a"}}):t==="DOWN"?e.jsx(Ye,{style:{color:"#ff4d4f"}}):e.jsx(ze,{style:{color:"#faad14"}}),be=t=>{switch(t){case"Running":return"success";case"Pending":return"processing";case"Failed":case"Error":return"error";default:return"warning"}},Ge=[{title:s("monitor.instance"),dataIndex:"instance",key:"instance",render:t=>e.jsx(n,{code:!0,children:t})},{title:s("monitor.job"),dataIndex:"job",key:"job",render:t=>e.jsx(K,{children:t})},{title:s("monitor.status"),dataIndex:"status",key:"status",render:t=>e.jsxs(m,{children:[Le(t),e.jsx(K,{color:Fe(t),children:t})]})}],He=[{value:1,label:s("monitor.last_1h")||"Last 1 hour"},{value:6,label:s("monitor.last_6h")||"Last 6 hours"},{value:24,label:s("monitor.last_24h")||"Last 24 hours"},{value:72,label:s("monitor.last_72h")||"Last 72 hours"},{value:168,label:s("monitor.last_7d")||"Last 7 days"}],ie=()=>N?e.jsx("div",{style:{textAlign:"center",padding:40},children:e.jsx(me,{})}):!i||(i.heapMemory?.length||0)===0?e.jsx(V,{message:s("monitor.history_unavailable")||"History data unavailable",description:s("monitor.history_unavailable_desc")||"Prometheus needs to be able to scrape gateway metrics to display history trends.",type:"info",showIcon:!0}):null,Ne=[{key:"jvm",label:e.jsxs(m,{children:[e.jsx(_e,{}),s("monitor.tab_jvm_gc")||"JVM & GC"]}),children:N||!i||!i.heapMemory?.length?ie():e.jsxs(j,{gutter:[16,16],children:[e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.heap_memory_history")||"Heap Memory",data:i?.heapMemory||[],colorKey:"blue",unit:"MB",yAxisLabel:"MB",valueTransform:t=>t/(1024*1024)})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.eden_memory_history")||"Eden Space",data:i?.edenMemory||[],colorKey:"cyan",unit:"MB",yAxisLabel:"MB",valueTransform:t=>t/(1024*1024)})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.old_gen_memory_history")||"Old Gen",data:i?.oldGenMemory||[],colorKey:"purple",unit:"MB",yAxisLabel:"MB",valueTransform:t=>t/(1024*1024)})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.non_heap_memory_history")||"Non-Heap Memory",data:i?.nonHeapMemory||[],colorKey:"orange",unit:"MB",yAxisLabel:"MB",valueTransform:t=>t/(1024*1024)})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.gc_time_history")||"GC Time (5min)",data:i?.gcTime||[],colorKey:"magenta",unit:"s",yAxisLabel:"s"})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.gc_count_history")||"GC Count (5min)",data:i?.gcCount||[],colorKey:"red",unit:"次",yAxisLabel:"次"})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.young_gc_history")||"Young GC (5min)",data:i?.youngGcCount||[],colorKey:"green",unit:"次",yAxisLabel:"次"})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.old_gc_history")||"Old/Full GC (5min)",data:i?.oldGcCount||[],colorKey:"red",unit:"次",yAxisLabel:"次"})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.allocation_rate_history")||"Memory Allocation Rate",data:i?.allocationRate||[],colorKey:"orange",unit:"MB/s",yAxisLabel:"MB/s",valueTransform:t=>t/(1024*1024)})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.promotion_rate_history")||"Promotion Rate History",data:i?.promotionRate||[],colorKey:"magenta",unit:"MB/s",yAxisLabel:"MB/s",valueTransform:t=>t/(1024*1024)})})]})},{key:"threads",label:e.jsxs(m,{children:[e.jsx(ye,{}),s("monitor.tab_threads")||"Threads"]}),children:N||!i||!i.threadCount?.length?ie():e.jsxs(j,{gutter:[16,16],children:[e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.thread_count_history")||"Thread Count",data:i?.threadCount||[],colorKey:"blue",unit:"",yAxisLabel:""})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.daemon_thread_history")||"Daemon Threads",data:i?.daemonThreadCount||[],colorKey:"cyan",unit:"",yAxisLabel:""})})]})},{key:"cpu",label:e.jsxs(m,{children:[e.jsx(Ce,{}),s("monitor.tab_cpu")||"CPU"]}),children:N||!i||!i.processCpuUsage?.length?ie():e.jsxs(j,{gutter:[16,16],children:[e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.process_cpu_history")||"Process CPU Usage (Gateway)",data:i?.processCpuUsage||[],colorKey:"green",unit:"%",yAxisLabel:"%",valueTransform:t=>t*100})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.system_load_history")||"System Load Average (1m)",data:i?.systemLoadAverage||[],colorKey:"cyan",unit:"",yAxisLabel:""})})]})},{key:"http",label:e.jsxs(m,{children:[e.jsx(Te,{}),s("monitor.tab_http")||"HTTP Requests"]}),children:N||!i||!i.requestRate?.length?ie():e.jsxs(j,{gutter:[16,16],children:[e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.request_rate_history")||"Request Rate",data:i?.requestRate||[],colorKey:"purple",unit:"/s",yAxisLabel:"/s"})}),e.jsx(r,{xs:24,lg:12,children:e.jsx(v,{title:s("monitor.response_time_history")||"Response Time",data:i?.responseTime||[],colorKey:"orange",unit:"ms",yAxisLabel:"ms",valueTransform:t=>t*1e3})})]})},{key:"routes",label:e.jsxs(m,{children:[e.jsx(je,{}),s("monitor.tab_routes")||"Routes"]}),children:a?e.jsx("div",{style:{textAlign:"center",padding:40},children:e.jsx(me,{})}):oe.length===0?e.jsx(V,{message:s("monitor.routes_no_data")||"No route metrics available",description:s("monitor.routes_no_data_desc")||"No route-level metrics found in the selected time range. Make sure the gateway is receiving requests.",type:"info",showIcon:!0}):e.jsx(ke,{dataSource:oe,rowKey:t=>`${t.uri}-${t.method}`,pagination:{pageSize:10,showSizeChanger:!0,showTotal:t=>`${t} routes`},size:"small",columns:[{title:s("monitor.route_uri")||"URI",dataIndex:"uri",key:"uri",render:t=>e.jsx(n,{code:!0,style:{fontSize:12},children:t}),sorter:(t,c)=>t.uri.localeCompare(c.uri)},{title:s("monitor.route_method")||"Method",dataIndex:"method",key:"method",width:80,render:t=>e.jsx(K,{color:t==="GET"?"blue":t==="POST"?"green":t==="PUT"?"orange":t==="DELETE"?"red":"default",children:t})},{title:s("monitor.route_requests")||"Requests",dataIndex:"requestCount",key:"requestCount",width:100,render:t=>e.jsx(n,{strong:!0,children:t.toLocaleString()}),sorter:(t,c)=>t.requestCount-c.requestCount,defaultSortOrder:"descend"},{title:s("monitor.route_errors")||"Errors",dataIndex:"errorCount",key:"errorCount",width:80,render:t=>e.jsx(n,{type:t>0?"danger":"secondary",children:t.toLocaleString()})},{title:s("monitor.route_error_rate")||"Error Rate",dataIndex:"errorRate",key:"errorRate",width:100,render:t=>e.jsx(D,{percent:t,size:"small",strokeColor:t>10?"#ff4d4f":t>5?"#faad14":"#52c41a",format:c=>`${c?.toFixed(1)}%`}),sorter:(t,c)=>t.errorRate-c.errorRate},{title:s("monitor.route_avg_time")||"Avg Time",dataIndex:"avgResponseTimeMs",key:"avgResponseTimeMs",width:100,render:t=>e.jsxs(n,{style:{color:t>1e3?"#ff4d4f":t>500?"#faad14":"inherit"},children:[t," ms"]}),sorter:(t,c)=>t.avgResponseTimeMs-c.avgResponseTimeMs},{title:s("monitor.route_throughput")||"Throughput",dataIndex:"throughputPerMin",key:"throughputPerMin",width:100,render:t=>e.jsxs(n,{children:[t.toFixed(1)," /min"]}),sorter:(t,c)=>t.throughputPerMin-c.throughputPerMin},{title:s("monitor.route_health")||"Health",dataIndex:"healthStatus",key:"healthStatus",width:100,render:t=>e.jsx(K,{color:t==="HEALTHY"?"success":t==="WARNING"?"warning":"error",children:t}),filters:[{text:"HEALTHY",value:"HEALTHY"},{text:"WARNING",value:"WARNING"},{text:"CRITICAL",value:"CRITICAL"}],onFilter:(t,c)=>c.healthStatus===t}]})}];return B&&!o?e.jsxs("div",{style:{textAlign:"center",padding:100},children:[e.jsx(me,{size:"large"}),e.jsx("div",{style:{marginTop:16},children:e.jsx(n,{type:"secondary",children:s("common.loading")})})]}):e.jsxs("div",{className:"monitor-page",children:[e.jsxs("div",{className:"page-header",children:[e.jsxs(Je,{level:4,style:{margin:0},children:[e.jsx(We,{style:{marginRight:8}}),s("monitor.title")]}),e.jsxs(m,{children:[e.jsxs(J.Group,{value:P,onChange:t=>Q(t.target.value),children:[e.jsxs(J.Button,{value:"realtime",children:[e.jsx(Re,{})," ",s("monitor.realtime_mode")||"实时模式"]}),e.jsxs(J.Button,{value:"historical",children:[e.jsx(ue,{})," ",s("monitor.historical_mode")||"历史模式"]})]}),P==="historical"&&e.jsx(fe,{value:A,onChange:$,style:{width:120},options:He}),e.jsx(G,{type:"primary",icon:e.jsx(Pe,{}),onClick:()=>q(!0),disabled:!k,children:s("ai.ai_analysis")||"AI分析"}),e.jsx(we,{status:k?"success":"error",text:s(k?"monitor.prometheus_connected":"monitor.prometheus_disconnected")}),e.jsxs(n,{type:"secondary",children:[e.jsx(ue,{style:{marginRight:4}}),new Date().toLocaleTimeString()]})]})]}),P==="historical"&&e.jsx(V,{message:s("monitor.historical_mode_hint"),description:s("monitor.historical_mode_desc_simple",{hours:A}),type:"info",showIcon:!0,closable:!0,style:{marginBottom:16}}),!k&&e.jsx(V,{message:s("monitor.prometheus_unavailable"),description:s("monitor.prometheus_unavailable_desc"),type:"warning",showIcon:!0,style:{marginBottom:16}}),f&&e.jsx(V,{message:s("common.error"),description:f,type:"error",showIcon:!0,closable:!0,style:{marginBottom:16},onClose:()=>x(null)}),b&&F.length>0&&e.jsx(_,{style:{marginBottom:16,overflow:"visible"},styles:{body:{padding:"12px 16px",overflow:"visible"}},children:e.jsxs(j,{gutter:16,align:"middle",style:{overflow:"visible"},children:[e.jsx(r,{style:{overflow:"visible"},children:e.jsxs(m,{style:{overflow:"visible"},children:[e.jsx(Me,{}),e.jsx(n,{strong:!0,children:s("monitor.select_pod")||"选择Pod"}),e.jsx(fe,{value:S?.name,onChange:$e,style:{width:280},loading:xe,getPopupContainer:t=>t.parentNode,listHeight:200,suffixIcon:e.jsx(Ve,{onClick:ae,style:{cursor:"pointer"}}),children:F.map(t=>e.jsx(fe.Option,{value:t.name,children:e.jsxs(m,{children:[e.jsx(we,{status:be(t.phase)}),e.jsx(n,{children:t.name}),e.jsx(n,{type:"secondary",style:{fontSize:12},children:t.podIP})]})},t.name))})]})}),S&&e.jsx(r,{flex:"auto",children:e.jsxs(m,{size:"large",children:[e.jsx(K,{color:be(S.phase),children:S.phase}),e.jsxs(n,{type:"secondary",children:["IP: ",e.jsx(n,{code:!0,children:S.podIP})]}),S.startTime&&e.jsxs(n,{type:"secondary",children:[s("monitor.pod_start_time")||"启动时间",": ",new Date(S.startTime).toLocaleString()]}),S.containers&&S.containers.length>0&&e.jsxs(n,{type:"secondary",children:[s("monitor.container_ready")||"容器就绪",":",S.containers.filter(t=>t.ready).length,"/",S.containers.length]})]})})]})}),e.jsx(_,{title:e.jsxs(m,{children:[e.jsx(Me,{}),s("monitor.gateway_instances")]}),style:{marginBottom:16},children:e.jsx(ke,{dataSource:o?.instances||[],columns:Ge,rowKey:"instance",pagination:!1,size:"small",locale:{emptyText:s("monitor.no_instances")}})}),e.jsxs(j,{gutter:[16,16],children:[e.jsx(r,{xs:24,sm:12,lg:6,children:e.jsxs(_,{title:e.jsxs(m,{children:[e.jsx(_e,{}),s("monitor.jvm_memory")]}),className:"metric-card",styles:{body:{padding:16}},children:[e.jsxs("div",{style:{marginBottom:12},children:[e.jsx(n,{type:"secondary",children:s("monitor.heap_usage")}),e.jsx(D,{percent:o?.jvmMemory?.heapUsagePercent||0,status:(o?.jvmMemory?.heapUsagePercent||0)>80?"exception":"normal",format:t=>`${t?.toFixed(1)}%`})]}),e.jsxs(j,{gutter:8,children:[e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.heap_used"),value:M(o?.jvmMemory?.heapUsed||0).value,suffix:M(o?.jvmMemory?.heapUsed||0).unit,precision:2,valueStyle:{fontSize:14}})}),e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.heap_max"),value:M(o?.jvmMemory?.heapMax||0).value,suffix:M(o?.jvmMemory?.heapMax||0).unit,precision:2,valueStyle:{fontSize:14}})})]}),o?.gc?.memoryRegions&&e.jsxs(e.Fragment,{children:[e.jsx(z,{style:{margin:"12px 0"}}),e.jsx(n,{type:"secondary",style:{fontSize:12,marginBottom:8,display:"block"},children:s("monitor.memory_regions")||"Memory Regions"}),e.jsxs(j,{gutter:[8,8],children:[e.jsxs(r,{span:24,children:[e.jsxs("div",{style:{marginBottom:4},children:[e.jsx(n,{style:{fontSize:12},children:"Eden Space"}),e.jsx(D,{percent:o?.gc?.memoryRegions?.eden?.usagePercent||0,size:"small",strokeColor:"#1890ff",format:t=>`${t?.toFixed(1)}%`})]}),e.jsxs(n,{type:"secondary",style:{fontSize:11},children:[M(o?.gc?.memoryRegions?.eden?.usedBytes||0).display," / ",M(o?.gc?.memoryRegions?.eden?.maxBytes||0).display]})]}),e.jsxs(r,{span:24,children:[e.jsxs("div",{style:{marginBottom:4},children:[e.jsx(n,{style:{fontSize:12},children:"Survivor Space"}),e.jsx(D,{percent:o?.gc?.memoryRegions?.survivor?.usagePercent||0,size:"small",strokeColor:"#52c41a",format:t=>`${t?.toFixed(1)}%`})]}),e.jsxs(n,{type:"secondary",style:{fontSize:11},children:[M(o?.gc?.memoryRegions?.survivor?.usedBytes||0).display," / ",M(o?.gc?.memoryRegions?.survivor?.maxBytes||0).display]})]}),e.jsxs(r,{span:24,children:[e.jsxs("div",{style:{marginBottom:4},children:[e.jsx(n,{style:{fontSize:12},children:"Old Gen"}),e.jsx(D,{percent:o?.gc?.memoryRegions?.oldGen?.usagePercent||0,size:"small",strokeColor:(o?.gc?.memoryRegions?.oldGen?.usagePercent||0)>70?"#ff4d4f":"#722ed1",format:t=>`${t?.toFixed(1)}%`})]}),e.jsxs(n,{type:"secondary",style:{fontSize:11},children:[M(o?.gc?.memoryRegions?.oldGen?.usedBytes||0).display," / ",M(o?.gc?.memoryRegions?.oldGen?.maxBytes||0).display]})]})]})]})]})}),e.jsx(r,{xs:24,sm:12,lg:6,children:e.jsxs(_,{title:e.jsxs(m,{children:[e.jsx(je,{}),s("monitor.gc_status")||"GC Status"]}),className:"metric-card",styles:{body:{padding:16}},children:[e.jsxs("div",{style:{marginBottom:12},children:[e.jsx(K,{color:o?.gc?.healthStatus==="HEALTHY"?"success":o?.gc?.healthStatus==="WARNING"?"warning":"error",children:o?.gc?.healthStatus||"UNKNOWN"}),o?.gc?.healthStatus!=="HEALTHY"&&o?.gc?.healthReason&&e.jsx(V,{message:o?.gc?.healthReason,type:o?.gc?.healthStatus==="WARNING"?"warning":"error",showIcon:!0,style:{marginTop:8,fontSize:12}}),o?.gc?.healthStatus==="HEALTHY"&&o?.gc?.healthReason&&e.jsx(n,{type:"secondary",style:{fontSize:12,marginTop:4},children:o?.gc?.healthReason})]}),e.jsxs(j,{gutter:8,children:[e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.gc_count")||"GC Count",value:o?.gc?.gcCount||0,suffix:s("monitor.times")||"次",valueStyle:{fontSize:14}})}),e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.gc_time")||"GC Time",value:o?.gc?.gcTimeSeconds?.toFixed(2)||"0",suffix:"s",valueStyle:{fontSize:14}})})]}),e.jsx(z,{style:{margin:"8px 0"}}),e.jsx(h,{title:s("monitor.gc_overhead")||"GC Overhead",value:o?.gc?.gcOverheadPercent?.toFixed(2)||"0",suffix:"%",valueStyle:{fontSize:14,color:(o?.gc?.gcOverheadPercent||0)>10?"#ff4d4f":"#52c41a"}}),o?.gc?.gcByType&&e.jsxs(e.Fragment,{children:[e.jsx(z,{style:{margin:"12px 0"}}),e.jsx(n,{type:"secondary",style:{fontSize:12,marginBottom:8,display:"block"},children:s("monitor.gc_breakdown")||"GC Breakdown"}),e.jsxs(j,{gutter:[8,8],children:[e.jsx(r,{span:24,children:e.jsxs("div",{style:{padding:"8px 12px",background:"rgba(59, 130, 246, 0.12)",borderRadius:4,border:"1px solid rgba(59, 130, 246, 0.2)"},children:[e.jsx(n,{strong:!0,style:{fontSize:12,color:"#60a5fa"},children:"Young GC"}),e.jsxs(j,{gutter:8,style:{marginTop:4},children:[e.jsxs(r,{span:8,children:[e.jsx(n,{type:"secondary",style:{fontSize:11},children:"次数"}),e.jsx("div",{style:{fontSize:13,fontWeight:500,color:"#fafafa"},children:o?.gc?.gcByType?.youngGC?.count||0})]}),e.jsxs(r,{span:8,children:[e.jsx(n,{type:"secondary",style:{fontSize:11},children:"总耗时"}),e.jsxs("div",{style:{fontSize:13,fontWeight:500,color:"#fafafa"},children:[o?.gc?.gcByType?.youngGC?.totalTimeSeconds?.toFixed(2)||"0","s"]})]}),e.jsxs(r,{span:8,children:[e.jsx(n,{type:"secondary",style:{fontSize:11},children:"平均耗时"}),e.jsxs("div",{style:{fontSize:13,fontWeight:500,color:"#fafafa"},children:[o?.gc?.gcByType?.youngGC?.avgTimeMs?.toFixed(1)||"0","ms"]})]})]})]})}),e.jsx(r,{span:24,children:e.jsxs("div",{style:{padding:"8px 12px",background:(o?.gc?.gcByType?.oldGC?.count||0)>0?"rgba(239, 68, 68, 0.12)":"rgba(255, 255, 255, 0.06)",borderRadius:4,border:(o?.gc?.gcByType?.oldGC?.count||0)>0?"1px solid rgba(239, 68, 68, 0.2)":"1px solid rgba(255, 255, 255, 0.1)"},children:[e.jsx(n,{strong:!0,style:{fontSize:12,color:(o?.gc?.gcByType?.oldGC?.count||0)>0?"#f87171":"#a1a1aa"},children:"Old/Full GC"}),e.jsxs(j,{gutter:8,style:{marginTop:4},children:[e.jsxs(r,{span:8,children:[e.jsx(n,{type:"secondary",style:{fontSize:11},children:"次数"}),e.jsx("div",{style:{fontSize:13,fontWeight:500,color:(o?.gc?.gcByType?.oldGC?.count||0)>0?"#f87171":"#fafafa"},children:o?.gc?.gcByType?.oldGC?.count||0})]}),e.jsxs(r,{span:8,children:[e.jsx(n,{type:"secondary",style:{fontSize:11},children:"总耗时"}),e.jsxs("div",{style:{fontSize:13,fontWeight:500,color:"#fafafa"},children:[(o?.gc?.gcByType?.oldGC?.count||0)>0&&o?.gc?.gcByType?.oldGC?.totalTimeSeconds?.toFixed(2)||"0","s"]})]}),e.jsxs(r,{span:8,children:[e.jsx(n,{type:"secondary",style:{fontSize:11},children:"平均耗时"}),e.jsxs("div",{style:{fontSize:13,fontWeight:500,color:"#fafafa"},children:[(o?.gc?.gcByType?.oldGC?.count||0)>0&&o?.gc?.gcByType?.oldGC?.avgTimeMs?.toFixed(1)||"0","ms"]})]})]})]})})]})]}),o?.gc?.allocationRateMBPerSec&&e.jsxs(e.Fragment,{children:[e.jsx(z,{style:{margin:"12px 0"}}),e.jsx(h,{title:s("monitor.allocation_rate")||"Memory Allocation Rate",value:o?.gc?.allocationRateMBPerSec?.toFixed(2)||"0",suffix:"MB/s",valueStyle:{fontSize:14}})]}),o?.gc?.promotionRateMBPerSec!==void 0&&o?.gc?.promotionRateMBPerSec>0&&e.jsxs(e.Fragment,{children:[e.jsx(z,{style:{margin:"8px 0"}}),e.jsxs(j,{gutter:8,children:[e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.promotion_rate")||"Promotion Rate",value:o?.gc?.promotionRateMBPerSec?.toFixed(2)||"0",suffix:"MB/s",valueStyle:{fontSize:14,color:o?.gc?.promotionRateMBPerSec>10?"#faad14":"#52c41a"}})}),e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.promotion_ratio")||"Promotion Ratio",value:o?.gc?.promotionRatio?.toFixed(1)||"0",suffix:"%",valueStyle:{fontSize:14,color:(o?.gc?.promotionRatio||0)>30?"#faad14":"#52c41a"}})})]})]})]})}),e.jsx(r,{xs:24,sm:12,lg:6,children:e.jsxs(_,{title:e.jsxs(m,{children:[e.jsx(Te,{}),s("monitor.http_requests")]}),className:"metric-card",styles:{body:{padding:16}},children:[e.jsx(h,{title:s("monitor.requests_per_second"),value:Number(o?.httpRequests?.requestsPerSecond||0).toFixed(2),suffix:s("monitor.per_second"),valueStyle:{color:"#1890ff",fontSize:20}}),e.jsx(z,{style:{margin:"8px 0"}}),e.jsxs(j,{gutter:8,children:[e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.avg_response_time"),value:o?.httpRequests?.avgResponseTimeMs||0,suffix:"ms",valueStyle:{fontSize:14}})}),e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.error_rate"),value:o?.httpRequests?.errorRate?.toFixed(2)||"0.00",suffix:"%",valueStyle:{fontSize:14,color:(o?.httpRequests?.errorRate||0)>1?"#ff4d4f":"#52c41a"}})})]})]})}),e.jsx(r,{xs:24,sm:12,lg:6,children:e.jsxs(_,{title:e.jsxs(m,{children:[e.jsx(Ce,{}),s("monitor.cpu_usage")]}),className:"metric-card",styles:{body:{padding:16}},children:[e.jsxs("div",{style:{marginBottom:8},children:[e.jsx(n,{type:"secondary",children:s("monitor.process_cpu")}),e.jsx(D,{percent:o?.cpu?.processUsage||0,status:(o?.cpu?.processUsage||0)>80?"exception":"normal"})]}),e.jsxs("div",{style:{marginBottom:8},children:[e.jsx(n,{type:"secondary",children:s("monitor.system_load")||"系统负载 (1m)"}),e.jsx(n,{strong:!0,style:{fontSize:18,display:"block",marginTop:4},children:o?.cpu?.systemLoadAverage||0})]}),e.jsx(h,{title:s("monitor.available_processors"),value:o?.cpu?.availableProcessors||0,valueStyle:{fontSize:14}})]})})]}),e.jsxs(j,{gutter:[16,16],style:{marginTop:16},children:[e.jsx(r,{xs:24,sm:12,lg:6,children:e.jsxs(_,{title:e.jsxs(m,{children:[e.jsx(ye,{}),s("monitor.threads")||"Threads"]}),className:"metric-card",styles:{body:{padding:16}},children:[e.jsx(h,{title:s("monitor.live_threads")||"Live Threads",value:o?.threads?.liveThreads||0,valueStyle:{fontSize:16,color:"#1890ff"}}),e.jsx(z,{style:{margin:"8px 0"}}),e.jsxs(j,{gutter:8,children:[e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.daemon_threads")||"Daemon Threads",value:o?.threads?.daemonThreads||0,valueStyle:{fontSize:14}})}),e.jsx(r,{span:12,children:e.jsx(h,{title:s("monitor.peak_threads")||"Peak Threads",value:o?.threads?.peakThreads||0,valueStyle:{fontSize:14}})})]}),o?.threads&&e.jsx("div",{style:{marginTop:8},children:e.jsxs(n,{type:"secondary",style:{fontSize:12},children:[s("monitor.user_threads")||"User Threads",": ",(o?.threads?.liveThreads||0)-(o?.threads?.daemonThreads||0)]})})]})}),e.jsx(r,{xs:24,sm:12,lg:6,children:e.jsxs(_,{title:e.jsxs(m,{children:[e.jsx(ue,{}),s("monitor.process_info")||"Process Info"]}),className:"metric-card",styles:{body:{padding:16}},children:[e.jsx(h,{title:s("monitor.uptime")||"Uptime",value:o?.process?.uptimeFormatted||"0h 0m 0s",valueStyle:{fontSize:16}}),e.jsx(z,{style:{margin:"8px 0"}}),e.jsx(h,{title:s("monitor.uptime_seconds")||"Uptime (seconds)",value:o?.process?.uptimeSeconds||0,suffix:"s",valueStyle:{fontSize:14}})]})}),e.jsx(r,{xs:24,sm:12,lg:6,children:e.jsxs(_,{title:e.jsxs(m,{children:[e.jsx(ye,{}),s("monitor.non_heap_memory")||"Non-Heap Memory"]}),className:"metric-card",styles:{body:{padding:16}},children:[e.jsx(h,{title:s("monitor.non_heap_used")||"Non-Heap Used",value:M(o?.jvmMemory?.nonHeapUsed||0).value,suffix:M(o?.jvmMemory?.nonHeapUsed||0).unit,precision:2,valueStyle:{fontSize:16}}),e.jsx(z,{style:{margin:"8px 0"}}),e.jsx(n,{type:"secondary",style:{fontSize:12},children:s("monitor.non_heap_desc")||"Metaspace, Code Cache, etc."})]})}),e.jsx(r,{xs:24,sm:12,lg:6,children:e.jsxs(_,{title:e.jsxs(m,{children:[e.jsx(ze,{}),s("monitor.log_events")||"日志事件统计"]}),className:"metric-card",styles:{body:{padding:16}},children:[e.jsx(h,{title:s("monitor.error_count")||"错误日志",value:o?.logEvents?.errorCount||0,valueStyle:{fontSize:16,color:(o?.logEvents?.errorCount||0)>100?"#ff4d4f":"#52c41a"}}),e.jsx(z,{style:{margin:"8px 0"}}),e.jsxs(j,{gutter:8,children:[e.jsx(r,{span:8,children:e.jsx(h,{title:s("monitor.warn_count")||"警告日志",value:o?.logEvents?.warnCount||0,valueStyle:{fontSize:14,color:(o?.logEvents?.warnCount||0)>50?"#faad14":"inherit"}})}),e.jsx(r,{span:8,children:e.jsx(h,{title:s("monitor.info_count")||"信息日志",value:o?.logEvents?.infoCount||0,valueStyle:{fontSize:14}})}),e.jsx(r,{span:8,children:e.jsx(h,{title:s("monitor.uptime")||"运行时间",value:o?.process?.uptimeFormatted||"0h 0m",valueStyle:{fontSize:14}})})]})]})})]}),e.jsx(_,{title:e.jsxs(m,{children:[e.jsx(De,{}),s("monitor.history_trends")||"History Trends"]}),extra:e.jsx(G,{icon:e.jsx(Re,{}),onClick:()=>ne(!0),loading:N,size:"small",children:s("common.refresh")||"Refresh"}),style:{marginTop:16},children:e.jsx(Be,{items:Ne,defaultActiveKey:"jvm"})}),e.jsx(Xe,{visible:Z,onClose:()=>q(!1),language:X.language?.startsWith("zh")?"zh":"en"}),e.jsx("style",{children:`
.monitor-page{padding:0;background:var(--bg-base)}
.page-header{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}
.metric-card{height:100%;background:var(--bg-secondary)}
.metric-card .ant-card-body{background:var(--bg-secondary)}
.ant-card{background:var(--bg-secondary)!important;border-color:var(--border-default)!important}
.ant-statistic-title{color:var(--text-secondary)!important}
.ant-statistic-content{color:var(--text-primary)!important}
.ant-progress-text{color:var(--text-primary)!important}
.ant-divider{border-color:var(--border-default)!important}
.ant-tag{background:rgba(255,255,255,0.08)!important;border-color:rgba(255,255,255,0.15)!important}
.ant-select-dropdown{top:auto!important;bottom:auto!important}
`})]})};export{at as default};
