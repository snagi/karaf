/*
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
package org.apache.karaf.itests.features;

import org.apache.karaf.itests.KarafTestSupport;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class StandardFeaturesTest extends KarafTestSupport {
	
	@Test
	/**
	 * Regression test for https://issues.apache.org/jira/browse/KARAF-2566
	 * @throws Exception
	 */
	public void checkInteractionOfHttpAndAriesAnnotationFeature() throws Exception {
		installAssertAndUninstallFeatures("aries-annotation", "pax-http");
		installAssertAndUninstallFeatures("pax-http", "aries-annotation");
    }

    @Test
    public void installAriesAnnotationFeature() throws Exception {
        installAssertAndUninstallFeatures("aries-annotation");
    }

    @Test
    public void installAriesBlueprintWebFeature() throws Exception {
        installAssertAndUninstallFeatures("blueprint-web");
    }
    
    @Test
    public void installWrapperFeature() throws Exception {
        installAssertAndUninstallFeatures("wrapper");
    }
    
    @Test
    public void installObrFeature() throws Exception {
        installAssertAndUninstallFeatures("obr");
    }

    @Test
    public void installConfigFeature() throws Exception {
        installAssertAndUninstallFeatures("config");
    }
    
    @Test
    public void installPackageFeature() throws Exception {
        installAssertAndUninstallFeatures("package");
    }

    @Test
    public void installHttpFeature() throws Exception {
        installAssertAndUninstallFeatures("http");
    }

    @Test
    public void installHttpWhiteboardFeature() throws Exception {
        installAssertAndUninstallFeatures("http-whiteboard");
    }

    @Test
    public void installWarFeature() throws Exception {
        installAssertAndUninstallFeatures("war");
    }
    
    @Test
    public void installKarFeature() throws Exception {
        installAssertAndUninstallFeatures("kar");
    }

    @Test
    public void installWebConsoleFeature() throws Exception {
        installAssertAndUninstallFeatures("webconsole");
    }

    @Test
    public void installSSHFeature() throws Exception {
        installAssertAndUninstallFeatures("ssh");
    }
    
    @Test
    public void installManagementFeature() throws Exception {
        installAssertAndUninstallFeatures("management");
    }
    
    @Test
    public void installSchedulerFeature() throws Exception {
        installAssertAndUninstallFeatures("scheduler");
    }

    @Test
    public void installEventAdminFeature() throws Exception {
        installAssertAndUninstallFeatures("eventadmin");
    }

    @Test
    public void installJasyptEncryptionFeature() throws Exception {
        installAssertAndUninstallFeatures("jasypt-encryption");
    }

    @Test
    public void installScrFeature() throws Exception {
        installAssertAndUninstallFeatures("scr");
    }

}
