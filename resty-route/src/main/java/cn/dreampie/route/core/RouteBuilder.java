package cn.dreampie.route.core;

import cn.dreampie.common.entity.CaseInsensitiveMap;
import cn.dreampie.common.http.HttpMethod;
import cn.dreampie.common.util.analysis.ParamAttribute;
import cn.dreampie.common.util.analysis.ParamNamesScaner;
import cn.dreampie.route.config.InterceptorLoader;
import cn.dreampie.route.config.ResourceLoader;
import cn.dreampie.route.core.annotation.*;
import cn.dreampie.route.core.multipart.FILE;
import cn.dreampie.route.core.multipart.MultipartBuilder;
import cn.dreampie.route.interceptor.Interceptor;
import cn.dreampie.route.interceptor.InterceptorBuilder;
import cn.dreampie.route.valid.Validator;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Route Mapping
 */
public final class RouteBuilder {

  private ResourceLoader resourceLoader;
  private InterceptorLoader interceptorLoader;

  //对routes排序
  private Map<String, Map<String, Set<Route>>> routesMap = new CaseInsensitiveMap<Map<String, Set<Route>>>();

  public RouteBuilder(ResourceLoader resourceLoader, InterceptorLoader interceptorLoader) {
    this.resourceLoader = resourceLoader;
    this.interceptorLoader = interceptorLoader;
  }

  /**
   * 添加route
   */
  private void addRoute(String httpMethod, String apiPath, String methodPath, String des, MultipartBuilder multipartBuilder, Interceptor[] routeInters, Map<String, ParamAttribute> classParamNames, Class<? extends Validator>[] validClasses, Class<? extends Resource> resourceClazz, Method method) {
    Route route = new Route(resourceClazz, ParamNamesScaner.getParamNames(method, classParamNames), httpMethod, getApi(apiPath, methodPath), method, routeInters,
        des, getValidators(validClasses), multipartBuilder);
    //资源的标志
    if (apiPath.contains(Route.PARAM_PATTERN)) {
      throw new IllegalArgumentException("Api path could not contains pattern. Because this is a resource url.");
    }
    //httpMethod区分
    if (routesMap.containsKey(httpMethod)) {
      Map<String, Set<Route>> routesHttpMethodMap = routesMap.get(httpMethod);
      //url区分
      if (routesHttpMethodMap.containsKey(apiPath)) {
        Set<Route> routes = routesHttpMethodMap.get(apiPath);
        //判断重复
        for (Route r : routes) {
          if (r.getHttpMethod().equals(route.getHttpMethod()) && r.getPattern().equals(route.getPattern())) {
            throw new IllegalArgumentException("Same path pattern '" + route.getHttpMethod() + " " + route.getPattern() + "' (" + route.getResourceClass().getSimpleName() + ".java:" + route.getAllLineNumbers()[0] + ")");
          }
        }
        routesMap.get(httpMethod).get(apiPath).add(route);
      } else {
        routesMap.get(httpMethod).put(apiPath, newRouteDESCSet(route));
      }
    } else {
      routesMap.put(httpMethod, newRouteMap(apiPath, route));
    }
  }


  public void build() {
    InterceptorBuilder interceptorBuilder = new InterceptorBuilder();
    Interceptor[] defaultInters = interceptorLoader.getInterceptorArray();
    interceptorBuilder.addToInterceptorsMap(defaultInters);

    //文件上传的注解
    FILE file = null;
    DELETE delete = null;
    GET get = null;
    POST post = null;
    PUT put = null;
    PATCH patch = null;
    String apiPath = "";

    //文件上传构建器
    MultipartBuilder multipartBuilder = null;
    Interceptor[] methodInters;
    //当前路由所有的拦截器 包括resource的和method的
    Interceptor[] routeInters;
    //resource的拦截器
    Interceptor[] resourceInters;
    //获取参数
    Map<String, ParamAttribute> classParamNames;
    //当前resource的方法
    Method[] methods;

    //addResources
    for (Class<? extends Resource> resourceClazz : resourceLoader.getResources()) {
      resourceInters = interceptorBuilder.buildResourceInterceptors(resourceClazz);
      classParamNames = ParamNamesScaner.getParamNames(resourceClazz);

      apiPath = getApi(resourceClazz);
      //自己的方法
      if (Modifier.isAbstract(resourceClazz.getSuperclass().getModifiers())) {
        methods = resourceClazz.getMethods();
      } else {
        methods = resourceClazz.getDeclaredMethods();
      }
      //遍历方法看是不是 restful api
      for (Method method : methods) {

        methodInters = interceptorBuilder.buildMethodInterceptors(method);
        routeInters = interceptorBuilder.buildRouteInterceptors(defaultInters, resourceInters, resourceClazz, methodInters, method);

        //文件上传 构建器
        file = method.getAnnotation(FILE.class);
        if (file != null) {
          multipartBuilder = new MultipartBuilder(file.dir(), file.overwrite(), file.renamer(), file.max(), file.encoding(), file.allows());
        } else {
          multipartBuilder = null;
        }

        //delete 请求
        delete = method.getAnnotation(DELETE.class);
        if (delete != null) {
          addRoute(HttpMethod.DELETE, apiPath, delete.value(), delete.des(), multipartBuilder, routeInters, classParamNames, delete.valid(), resourceClazz, method);
          continue;
        }
        //get 请求
        get = method.getAnnotation(GET.class);
        if (get != null) {
          addRoute(HttpMethod.GET, apiPath, get.value(), get.des(), multipartBuilder, routeInters, classParamNames, get.valid(), resourceClazz, method);
          continue;
        }
        //post 请求
        post = method.getAnnotation(POST.class);
        if (post != null) {
          addRoute(HttpMethod.POST, apiPath, post.value(), post.des(), multipartBuilder, routeInters, classParamNames, post.valid(), resourceClazz, method);
          continue;
        }
        //put 请求
        put = method.getAnnotation(PUT.class);
        if (put != null) {
          addRoute(HttpMethod.PUT, apiPath, put.value(), put.des(), multipartBuilder, routeInters, classParamNames, put.valid(), resourceClazz, method);
          continue;
        }
        //patch 请求
        patch = method.getAnnotation(PATCH.class);
        if (patch != null) {
          addRoute(HttpMethod.PATCH, apiPath, patch.value(), patch.des(), multipartBuilder, routeInters, classParamNames, patch.valid(), resourceClazz, method);
          continue;
        }
      }
    }
  }


  /**
   * 获取所有验证器
   *
   * @param validClasses 验证器的class
   * @return Valid[]
   */
  private Validator[] getValidators(Class<? extends Validator>[] validClasses) {
    Validator[] validators = new Validator[validClasses.length];
    if (validClasses.length > 0) {
      int i = 0;
      for (Class<? extends Validator> valid : validClasses) {
        try {
          validators[i] = valid.newInstance();
        } catch (InstantiationException e) {
          throw new RuntimeException(e.getMessage(), e);
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e.getMessage(), e);
        }
        i++;
      }
    }
    return validators;
  }

  /**
   * 获取api部分
   *
   * @param resourceClazz resource class
   * @return url apiPath
   */
  private String getApi(Class<? extends Resource> resourceClazz) {
    API api;
    String apiPath = "";
    api = resourceClazz.getAnnotation(API.class);
    if (api != null) {
      apiPath = api.value();
      if (!apiPath.equals("")) {
        if (!apiPath.startsWith("/")) {
          apiPath = "/" + apiPath;
        }
      }
    }
    Class<?> superClazz = resourceClazz.getSuperclass();
    if (Resource.class.isAssignableFrom(superClazz)) {
      apiPath = getApi((Class<? extends Resource>) superClazz) + apiPath;
    }
    return apiPath;
  }

  /**
   * 最终生成的apiPath
   *
   * @param apiPath
   * @param methodPath
   * @return
   */
  private String getApi(String apiPath, String methodPath) {
    if (!methodPath.equals("")) {
      if (!methodPath.startsWith("/")) {
        apiPath = apiPath + "/" + methodPath;
      } else {
        apiPath = apiPath + methodPath;
      }
    }
    return apiPath;
  }

  public Map<String, Map<String, Set<Route>>> getRoutesMap() {
    return Collections.unmodifiableMap(routesMap);
  }

  /**
   * 创建一个对key排序的map
   *
   * @param apiPath apiPath
   * @param route   route
   * @return map
   */
  public Map<String, Set<Route>> newRouteMap(final String apiPath, final Route route) {
    return new TreeMap<String, Set<Route>>(new Comparator<String>() {
      public int compare(String k1, String k2) {
        int result = k2.length() - k1.length();
        if (result == 0) {
          return k1.compareTo(k2);
        }
        return result;
      }
    }) {{
      put(apiPath, newRouteDESCSet(route));
    }};
  }

  /**
   * 创建一个倒排序的route
   *
   * @param route route
   * @return Set
   */
  public Set<Route> newRouteDESCSet(final Route route) {
    return new TreeSet<Route>(
        new Comparator<Route>() {
          public int compare(Route a, Route b) {
            String one = a.getPattern().replace("/" + Route.PARAM_PATTERN, "");
            String two = b.getPattern().replace("/" + Route.PARAM_PATTERN, "");
            int result = two.length() - one.length();
            if (result == 0) {
              result = a.getHttpMethod().compareTo(b.getHttpMethod());
              if (result == 0) {
                return a.getPathPattern().compareTo(b.getPathPattern());
              }
            }
            return result;
          }
        }) {{
      add(route);
    }};
  }
}





