package com.github.chuanzh.webframe.filter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

import com.github.chuanzh.orm.DbBasicService;
import com.github.chuanzh.orm.DbConnectTool;
import com.github.chuanzh.orm.DbFactory;
import com.github.chuanzh.util.FuncFile;
import com.github.chuanzh.util.FuncRandom;
import com.github.chuanzh.util.FuncStatic;
import com.github.chuanzh.webframe.HtmlBuilder;
import com.github.chuanzh.webframe.annotation.IjDbService;
import com.github.chuanzh.webframe.annotation.IjHttpServletRequest;
import com.github.chuanzh.webframe.annotation.IjHttpServletResponse;
import com.github.chuanzh.webframe.annotation.IjParam;
import com.github.chuanzh.webframe.bean.HtmlPage;


public abstract class HttpFilter implements Filter {
	
	private static Logger logger = Logger.getLogger(HttpFilter.class);
	public static String ENCODE = null;
	private static int runTimeLimit = 10000;
	protected static HashMap<String, Class> actionList = new HashMap<String, Class>();

	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		try {
			this.ENCODE = projectEncode();  
			Set<Class<?>> set = FuncFile.getClasses(controlFolder());
			for (Class c : set) {
				String p = c.getName();
				String key = p
						.substring(p.indexOf(".control.") + 8, p.length());
				actionList.put(key.toLowerCase().replace('.', '/'), c);
			}
			if(this.getRunTimeLimit() != 0){
				runTimeLimit = this.getRunTimeLimit();
			}
			initHtmlBuilder();
		} catch (Exception e) {
			logger.error(FuncStatic.errorTrace(e));
		}
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		// TODO Auto-generated method stub
		try {
			request.setCharacterEncoding("UTF-8");
			response.setCharacterEncoding(ENCODE);
			response.setContentType("text/html; charset=" + ENCODE);
			startRequest((HttpServletRequest)request);
			long start = System.currentTimeMillis();
			HttpThread thread = new HttpThread();
			thread.setFilter(this);
			thread.setRequest((HttpServletRequest)request);
			thread.setResponse((HttpServletResponse)response);
			Thread runThread = new Thread(thread);
			try {
				runThread.start();
				runThread.join(runTimeLimit);
				if (runThread.isAlive()) {
					runThread.interrupt();
				}
			} catch (Exception e) {
				logger.error(FuncStatic.errorTrace(e));
			}
			
			HtmlPage htmlPage = thread.getHtmlPage();
			if (htmlPage == null) {
				htmlPage = new HtmlPage(false,"<h1>connection time out!</h1>");
			}
			
			if(htmlPage.getContent() instanceof HtmlBuilder){
				((HtmlBuilder)htmlPage.getContent()).html(response.getWriter());
			}else{
				response.getWriter().write(htmlPage.getContent().toString());
			}
			this.endRequest(System.currentTimeMillis()-start, (HttpServletRequest)request,htmlPage);
		} catch (Exception e) {
			logger.error(FuncStatic.errorTrace(e));
		}
	}

	
	@Override
	public void destroy() {
		// TODO Auto-generated method stub
		
	}
	
	public void startRequest(HttpServletRequest request) {
		String requestId = FuncRandom.createSequence();
		String ip = request.getRemoteAddr();
		StringBuilder sb = new StringBuilder();
		Map<String,String[]>map = request.getParameterMap();
		for(String o : map.keySet()){
			sb.append(o);
			sb.append("=");
			sb.append(map.get(o)[0]) ;
			sb.append("&"); 
		}
		request.setAttribute("requestId", requestId);
		String info = "request_start ["+ip+"]"+requestId+" "+ request.getRequestURI()+"?"+sb.toString();
		logger.info(info);
	}
	
	public void endRequest(long pageRunTime, HttpServletRequest request, HtmlPage htmlPage) {
		String requestId = String.valueOf(request.getAttribute("requestId"));
		if(!htmlPage.isSuccess()){
			String info = "request_end "+requestId+" pageRunTime:"+ pageRunTime + " Error:"+htmlPage.getContent();
			logger.info(info);
		}else{
			String info = "request_end "+requestId+" pageRunTime:"+ pageRunTime + " OK";
			logger.info(info);
		}
	}
	
	/**
	 * 初始化对象参数
	 * @param controlObject 对象
	 * @param request 请求对象
	 * @param response 响应对象
	 * @throws Exception 异常
	 */
	public void beforeDoControl(Object controlObject, HttpServletRequest request,HttpServletResponse response)  throws Exception {
		//初始化对象注入
		Field[] fileds = controlObject.getClass().getDeclaredFields();
		for (Field f : fileds) {
			f.setAccessible(true);
			String fileName = f.getName();
			if (f.isAnnotationPresent(IjParam.class)) {
				if (request.getParameter(fileName) != null) {
					try {
						String fvalue = request.getParameter(fileName);
						if (f.getType().equals(String.class)) {
							f.set(controlObject, fvalue);
						} else if (f.getType().equals(Integer.class)) {
							f.set(controlObject, Integer.parseInt(fvalue));
						} else if (f.getType().equals(Long.class)) {
							f.set(controlObject, Long.parseLong(fvalue));
						} else if (f.getType().equals(String[].class)) {
							f.set(controlObject, request.getParameterValues(fileName));
						}
					} catch (Exception e) {
					}
				}
				
			} else if (f.isAnnotationPresent(IjDbService.class)) {
				Class c = f.getAnnotation(IjDbService.class).value();
				Method m = c.getDeclaredMethod("instance", null);
				f.set(controlObject, DbFactory.instanceService((DbConnectTool)m.invoke(c, null)));
			} else if (f.isAnnotationPresent(IjHttpServletRequest.class)) {
				f.set(controlObject, request);
			} else if (f.isAnnotationPresent(IjHttpServletResponse.class)) {
				f.set(controlObject, response);
			}
		}
	}
	
	/**
	 * 释放资源
	 * @param o 数据库连接
	 * @throws Exception 异常
	 */
	public void releaseResource(Object o)  throws Exception {
		if(o != null){
			Field[] file = o.getClass().getDeclaredFields();
			for (int i = 0; i < file.length; i++) {
				Field f = file[i];
				f.setAccessible(true);
				if (f.isAnnotationPresent(IjDbService.class)){
					DbBasicService service = (DbBasicService)f.get(o);
					if(service !=null){
						service.freeResource();
					}
				}
			}
		}
	}
	
	public abstract int getRunTimeLimit();
	public abstract String projectEncode();
	public abstract String controlFolder();
	protected abstract String alias();
	protected abstract void initHtmlBuilder();


}
