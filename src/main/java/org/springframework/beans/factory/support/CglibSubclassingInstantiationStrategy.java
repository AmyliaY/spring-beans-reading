/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanFactory;

import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.CallbackFilter;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.cglib.proxy.NoOp;

/**
 * Default object instantiation strategy for use in BeanFactories.
 * Uses CGLIB to generate subclasses dynamically if methods need to be
 * overridden by the container, to implement Method Injection.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 1.1
 */
public class CglibSubclassingInstantiationStrategy extends SimpleInstantiationStrategy {

	/**
	 * Index in the CGLIB callback array for passthrough behavior,
	 * in which case the subclass won't override the original class.
	 */
	private static final int PASSTHROUGH = 0;

	/**
	 * Index in the CGLIB callback array for a method that should
	 * be overridden to provide method lookup.
	 */
	private static final int LOOKUP_OVERRIDE = 1;

	/**
	 * Index in the CGLIB callback array for a method that should
	 * be overridden using generic Methodreplacer functionality.
	 */
	private static final int METHOD_REPLACER = 2;


	//下面两个方法都通过实例化自己的私有静态内部类CglibSubclassCreator，
	//然后调用该内部类对象的实例化方法instantiate()完成实例化
	protected Object instantiateWithMethodInjection(
			RootBeanDefinition beanDefinition, String beanName, BeanFactory owner) {

		// 必须生成cglib子类
		return new CglibSubclassCreator(beanDefinition, owner).instantiate(null, null);
	}

	@Override
	protected Object instantiateWithMethodInjection(
			RootBeanDefinition beanDefinition, String beanName, BeanFactory owner,
			Constructor ctor, Object[] args) {

		return new CglibSubclassCreator(beanDefinition, owner).instantiate(ctor, args);
	}


	/**
	 * 为避免3.2之前的Spring版本中的外部cglib依赖而创建的内部类。
	 */
	private static class CglibSubclassCreator {

		private static final Log logger = LogFactory.getLog(CglibSubclassCreator.class);

		private final RootBeanDefinition beanDefinition;

		private final BeanFactory owner;

		public CglibSubclassCreator(RootBeanDefinition beanDefinition, BeanFactory owner) {
			this.beanDefinition = beanDefinition;
			this.owner = owner;
		}

		//使用CGLIB进行Bean对象实例化
		public Object instantiate(Constructor ctor, Object[] args) {
			//实例化Enhancer对象，并为Enhancer对象设置父类，生成Java对象的参数，比如：基类、回调方法等
			Enhancer enhancer = new Enhancer();
			//将Bean本身作为其父类
			enhancer.setSuperclass(this.beanDefinition.getBeanClass());
			enhancer.setCallbackFilter(new CallbackFilterImpl());
			enhancer.setCallbacks(new Callback[] {
					NoOp.INSTANCE,
					new LookupOverrideMethodInterceptor(),
					new ReplaceOverrideMethodInterceptor()
			});

			//使用CGLIB的create方法生成实例对象
			return (ctor == null) ? enhancer.create() : enhancer.create(ctor.getParameterTypes(), args);
		}


		/**
		 * Class providing hashCode and equals methods required by CGLIB to
		 * ensure that CGLIB doesn't generate a distinct class per bean.
		 * Identity is based on class and bean definition.
		 */
		private class CglibIdentitySupport {

			/**
			 * Exposed for equals method to allow access to enclosing class field
			 */
			protected RootBeanDefinition getBeanDefinition() {
				return beanDefinition;
			}

			@Override
			public boolean equals(Object other) {
				return (other.getClass().equals(getClass()) &&
						((CglibIdentitySupport) other).getBeanDefinition().equals(beanDefinition));
			}

			@Override
			public int hashCode() {
				return beanDefinition.hashCode();
			}
		}


		/**
		 * CGLIB MethodInterceptor to override methods, replacing them with an
		 * implementation that returns a bean looked up in the container.
		 */
		private class LookupOverrideMethodInterceptor extends CglibIdentitySupport implements MethodInterceptor {

			public Object intercept(Object obj, Method method, Object[] args, MethodProxy mp) throws Throwable {
				// Cast is safe, as CallbackFilter filters are used selectively.
				LookupOverride lo = (LookupOverride) beanDefinition.getMethodOverrides().getOverride(method);
				return owner.getBean(lo.getBeanName());
			}
		}


		/**
		 * CGLIB MethodInterceptor to override methods, replacing them with a call
		 * to a generic MethodReplacer.
		 */
		private class ReplaceOverrideMethodInterceptor extends CglibIdentitySupport implements MethodInterceptor {

			public Object intercept(Object obj, Method method, Object[] args, MethodProxy mp) throws Throwable {
				ReplaceOverride ro = (ReplaceOverride) beanDefinition.getMethodOverrides().getOverride(method);
				// TODO could cache if a singleton for minor performance optimization
				MethodReplacer mr = (MethodReplacer) owner.getBean(ro.getMethodReplacerBeanName());
				return mr.reimplement(obj, method, args);
			}
		}


		/**
		 * CGLIB object to filter method interception behavior.
		 */
		private class CallbackFilterImpl extends CglibIdentitySupport implements CallbackFilter {

			public int accept(Method method) {
				MethodOverride methodOverride = beanDefinition.getMethodOverrides().getOverride(method);
				if (logger.isTraceEnabled()) {
					logger.trace("Override for '" + method.getName() + "' is [" + methodOverride + "]");
				}
				if (methodOverride == null) {
					return PASSTHROUGH;
				}
				else if (methodOverride instanceof LookupOverride) {
					return LOOKUP_OVERRIDE;
				}
				else if (methodOverride instanceof ReplaceOverride) {
					return METHOD_REPLACER;
				}
				throw new UnsupportedOperationException(
						"Unexpected MethodOverride subclass: " + methodOverride.getClass().getName());
			}
		}
	}

}
