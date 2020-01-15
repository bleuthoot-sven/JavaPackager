package fvarrui.maven.plugin.javapackager;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;
import org.twdata.maven.mojoexecutor.MojoExecutor.ExecutionEnvironment;

import fvarrui.maven.plugin.javapackager.utils.FileUtils;
import fvarrui.maven.plugin.javapackager.utils.JavaUtils;
import fvarrui.maven.plugin.javapackager.utils.Logger;
import fvarrui.maven.plugin.javapackager.utils.ProcessUtils;
import fvarrui.maven.plugin.javapackager.utils.VelocityUtils;

@Mojo(name = "package", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.RUNTIME)
public class PackageMojo extends AbstractMojo {

	// maven components
	
	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject mavenProject;

	@Parameter(defaultValue = "${session}", readonly = true)
	private MavenSession mavenSession;

	@Component
	private BuildPluginManager pluginManager;

	@Component
	private MavenProjectHelper projectHelper;

	// private variables
	
	private ExecutionEnvironment env;

	private Map<String, Object> info;
	
	private Platform currentPlatform;

	private File debFile, appFolder, assetsFolder, jarFile, executable;

	// plugin configuration properties
	
	@Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
	private File outputDirectory;

	@Parameter(property = "licenseFile", required = false)
	private File licenseFile;

	@Parameter(property = "iconFile")
	private File iconFile;

	@Parameter(defaultValue = "${java.version}", property = "jreMinVersion", required = true)
	private String jreMinVersion;

	@Parameter(defaultValue = "true", property = "generateInstaller", required = true)
	private Boolean generateInstaller;

	@Parameter(property = "mainClass", required = true)
	private String mainClass;

	@Parameter(defaultValue = "${project.name}", property = "name", required = true)
	private String name;

	@Parameter(defaultValue = "${project.name}", property = "displayName", required = false)
	private String displayName;

	@Parameter(defaultValue = "${project.version}", property = "version", required = true)
	private String version;

	@Parameter(defaultValue = "${project.description}", property = "description", required = false)
	private String description;

	@Parameter(defaultValue = "${project.url}", property = "url", required = false)
	private String url;

	@Parameter(defaultValue = "false", property = "administratorRequired", required = true)
	private Boolean administratorRequired;

	@Parameter(defaultValue = "${project.organization.name}", property = "organizationName", required = false)
	private String organizationName;

	@Parameter(defaultValue = "${project.organization.url}", property = "organizationUrl", required = false)
	private String organizationUrl;

	@Parameter(defaultValue = "", property = "organizationEmail", required = false)
	private String organizationEmail;

	@Parameter(defaultValue = "false", property = "bundleJre", required = true)
	private Boolean bundleJre;
	
	@Parameter(defaultValue = "true", property = "customizedJre", required = false)
	private Boolean customizedJre;

	@Parameter(defaultValue = "", property = "jrePath", required = false)
	private String jrePath;

	@Parameter(property = "additionalResources", required = false)
	private List<File> additionalResources;

	@Parameter(property = "modules", required = false)
	private List<String> modules;

	@Parameter(property = "additionalModules", required = false)
	private List<String> additionalModules;

	@Parameter(defaultValue = "auto", property = "platform", required = true)
	private Platform platform;
	
	@Parameter(property = "path", required = false)
	private String path;	

	public PackageMojo() {
		super();
		Logger.init(getLog()); // sets Mojo's logger to Logger class, so it could be used from static methods
	}

	public void execute() throws MojoExecutionException {

		// gets plugin execution environment 
		this.env = executionEnvironment(mavenProject, mavenSession, pluginManager);

		// determines current platform
		currentPlatform = getCurrentPlatform();
		
		// determines target platform if not specified 
		if (platform == null || platform == Platform.auto) {
			platform = currentPlatform;
		}
		getLog().info("Packaging app for " + platform);

		// creates app destination folder
		appFolder = new File(outputDirectory, "app");
		if (!appFolder.exists()) {
			appFolder.mkdirs();
		}

		// creates folder for intermmediate assets 
		assetsFolder = new File(outputDirectory, "assets");
		if (!assetsFolder.exists()) {
			assetsFolder.mkdirs();
		}

		// sets app's main executable file 
		executable = new File(appFolder, name);

		// if default license file doesn't exist and there's a license specified in
		// pom.xml file, gets this last one
		if (licenseFile != null && !licenseFile.exists()) {
			getLog().warn("Specified license file doesn't exist: " + licenseFile.getAbsolutePath());
			licenseFile = null;
		}
		// if license not specified, gets from pom
		if (licenseFile == null && !mavenProject.getLicenses().isEmpty()) {
			licenseFile = new File(mavenProject.getLicenses().get(0).getUrl());
		}

		// creates a runnable jar file
		createRunnableJar();

		// collects app info 
		this.info = getInfo();

		// generates bundle depending on the specified target platform  
		switch (platform) {
		case mac:
			createMacApp();
			generateDmgImage();
			break;
		case linux:
			createLinuxApp();
			generateDebPackage();
			generateRpmPackage();
			break;
		case windows:
			createWindowsApp();
			generateWindowsInstaller();
			break;
		default:
			throw new MojoExecutionException("Unsupported operating system: " + SystemUtils.OS_NAME + " " + SystemUtils.OS_VERSION + " " + SystemUtils.OS_ARCH);
		}		

	}
	
	/**
	 * Locates assets or default icon file if the specified one doesn't exist or
	 * isn't specified
	 * 
	 * @param platform      Target platform
	 * @param iconExtension Icon file extension (.ico for Windows, .png for
	 *                      GNU/Linux, .icns for MacOS)
	 * @throws MojoExecutionException
	 */
	private void resolveIcon(Platform platform, String iconExtension) throws MojoExecutionException {
		if (iconFile == null) {
			iconFile = new File("assets/" + platform + "/", mavenProject.getName() + iconExtension);
		}
		if (!iconFile.exists()) {
			iconFile = new File(assetsFolder, iconFile.getName());
			FileUtils.copyResourceToFile("/" + platform + "/default-icon" + iconExtension, iconFile);
		}
	}

	/**
	 * Creates a runnable jar file from sources
	 * 
	 * @throws MojoExecutionException
	 */
	private void createRunnableJar() throws MojoExecutionException {
		getLog().info("Creating runnable JAR...");
		
		String classifier = "runnable";

		jarFile = new File(outputDirectory, mavenProject.getName() + "-" + mavenProject.getVersion() + "-" + classifier + "." + mavenProject.getPackaging());

		executeMojo(
				plugin(
						groupId("org.apache.maven.plugins"), 
						artifactId("maven-jar-plugin"), 
						version("3.1.1")
				),
				goal("jar"),
				configuration(
						element("classifier", classifier),
						element("archive", 
								element("manifest", 
										element("addClasspath", "true"),
										element("classpathPrefix", "libs/"),
										element("mainClass", mainClass)
								)
						),
						element("outputDirectory", jarFile.getParentFile().getAbsolutePath())
				),
				env);
	}

	/**
	 * Collects info needed for Velocity templates
	 * 
	 * @return Map with collected properties
	 * @throws MojoExecutionException
	 */
	private Map<String, Object> getInfo() throws MojoExecutionException {
		HashMap<String, Object> info = new HashMap<>();

		info.put("name", name);
		info.put("displayName", displayName);
		info.put("version", version);
		info.put("description", description);
		info.put("url", url);
		info.put("organizationName", organizationName);
		info.put("organizationUrl", organizationUrl == null ? "" : organizationUrl);
		info.put("organizationEmail", organizationEmail);
		info.put("administratorRequired", administratorRequired);
		info.put("bundleJre", bundleJre);
		info.put("mainClass", mainClass);
		info.put("jarFile", jarFile.getName());
		info.put("license", licenseFile != null ? licenseFile.getAbsolutePath() : "");
		info.put("path", path);
		
		return info;
	}

	/**
	 * Creates a RPM package file including all app folder's content only for 
	 * GNU/Linux so app could be easily distributed
	 * 
	 * @throws MojoExecutionException
	 */
	private void generateRpmPackage() throws MojoExecutionException {
		if (!generateInstaller || currentPlatform != Platform.linux) return;

		getLog().info("Generating RPM package...");

		if (!debFile.exists()) {
			getLog().warn("Cannot convert DEB to RPM because " + debFile.getAbsolutePath() + " doesn't exist");
			return;
		}

		try {
			// executes alien command to generate rpm package folder from deb file
			ProcessUtils.execute(assetsFolder, "alien", "-g", "--to-rpm", debFile);
		} catch (MojoExecutionException e) {
			getLog().warn("alien command execution failed", e);
			return;
		}

		File packageFolder = new File(assetsFolder, name.toLowerCase() + "-" + version);
		File specFile = new File(packageFolder, name + "-" + version + "-2.spec");

		try {
			// rebuilds rpm package
			ProcessUtils.execute(assetsFolder, "rpmbuild", "--buildroot", packageFolder, "--nodeps", "-bb", specFile);
		} catch (MojoExecutionException e) {
			getLog().warn("rpmbuild command execution failed", e);
			return;
		}

		// renames generated rpm package
		File rpmFile = new File(outputDirectory, name + "-" + version + "-2.x86_64.rpm");
		String newName = name + "_" + version + ".rpm";
		FileUtils.rename(rpmFile, newName);

	}

	/**
	 * Creates a native MacOS app bundle
	 * 
	 * @throws MojoExecutionException
	 */
	private void createMacApp() throws MojoExecutionException {
		getLog().info("Creating Mac OS X app bundle...");

		// creates and set up directories
		getLog().info("Creating and setting up the bundle directories");
		
		File appFile = new File(appFolder, name + ".app");
		appFile.mkdirs();

		File contentsFolder = new File(appFile, "Contents");
		contentsFolder.mkdirs();

		File resourcesFolder = new File(contentsFolder, "Resources");
		resourcesFolder.mkdirs();

		File javaFolder = new File(resourcesFolder, "Java");
		javaFolder.mkdirs();

		File macOSFolder = new File(contentsFolder, "MacOS");
		macOSFolder.mkdirs();

		// copies all dependencies to Java folder
		getLog().info("Copying dependencies to Java folder");
		File libsFolder = new File(javaFolder, "libs");
		copyAllDependencies(libsFolder);

		// copies jarfile to Java folder
		FileUtils.copyFileToFolder(jarFile, javaFolder);

		// checks if JRE should be embedded
		if (bundleJre) {
			File jreFolder = new File(contentsFolder, "PlugIns/jre/Contents/Home");
			bundleJre(jreFolder, libsFolder);
		}

		// creates startup file to boot java app
		getLog().info("Creating startup file");
		File startupFile = new File(macOSFolder, "startup");
		VelocityUtils.render("mac/startup.vtl", startupFile, info);
		startupFile.setExecutable(true, false);

		// determines icon file location and copies it to resources folder
		getLog().info("Copying icon file to Resources folder");
		resolveIcon(Platform.mac, ".icns");
		FileUtils.copyFileToFolder(iconFile.getAbsoluteFile(), resourcesFolder);

		// creates and write the Info.plist file
		getLog().info("Writing the Info.plist file");
		File infoPlistFile = new File(contentsFolder, "Info.plist");
		VelocityUtils.render("mac/Info.plist.vtl", infoPlistFile, info);

		// copies specified additional resources into the top level directory (include license file)
		if (licenseFile != null) additionalResources.add(licenseFile);
		copyAdditionalResources(additionalResources, resourcesFolder);

		// codesigns app folder
		if (currentPlatform == Platform.mac) {
			ProcessUtils.execute("codesign", "--force", "--deep", "--sign", "-", appFile);
		}

	}

	/**
	 * Creates a GNU/Linux app file structure with native executable
	 * 
	 * @throws MojoExecutionException
	 */
	private void createLinuxApp() throws MojoExecutionException {
		getLog().info("Creating GNU/Linux app bundle...");

		// determines icon file location and copies it to app folder
		resolveIcon(Platform.linux, ".png");
		FileUtils.copyFileToFolder(iconFile, appFolder);

		// copies all dependencies
		File libsFolder = new File(appFolder, "libs");
		copyAllDependencies(libsFolder);

		// copies additional resources
		if (licenseFile != null) additionalResources.add(licenseFile);
		copyAdditionalResources(additionalResources, appFolder);

		// checks if JRE should be embedded
		if (bundleJre) {
			File jreFolder = new File(appFolder, "jre");
			bundleJre(jreFolder, libsFolder);
		}

		// generates startup.sh script to boot java app
		File startupFile = new File(assetsFolder, "startup.sh");
		VelocityUtils.render("linux/startup.sh.vtl", startupFile, info);

		// concats linux startup.sh script + generated jar in executable (binary)
		FileUtils.concat(executable, startupFile, jarFile);

		// sets execution permissions
		executable.setExecutable(true, false);
		
	}

	/**
	 * Creates a Windows app file structure with native executable
	 * 
	 * @throws MojoExecutionException
	 */
	private void createWindowsApp() throws MojoExecutionException {
		getLog().info("Creating Windows app bundle...");
		
		// determines icon file location
		resolveIcon(Platform.windows, ".ico");

		// generates manifest file to require administrator privileges from velocity template
		File manifestFile = new File(assetsFolder, name + ".exe.manifest");
		VelocityUtils.render("windows/exe.manifest.vtl", manifestFile, info);
		
		// copies all dependencies
		File libsFolder = new File(appFolder, "libs");
		copyAllDependencies(libsFolder);
		
		// copies additional resources
		if (licenseFile != null) additionalResources.add(licenseFile);		
		copyAdditionalResources(additionalResources, appFolder);
		
		// checks if JRE should be embedded
		if (bundleJre) {
			File jreFolder = new File(appFolder, "jre");
			bundleJre(jreFolder, libsFolder);
		}
		
		// prepares launch4j plugin configuration
		
		List<Element> config = new ArrayList<>();
		
		config.add(element("headerType", "gui"));
		config.add(element("jar", jarFile.getAbsolutePath()));
		config.add(element("outfile", executable.getAbsolutePath() + ".exe"));
		config.add(element("icon", iconFile.getAbsolutePath()));
		config.add(element("manifest", manifestFile.getAbsolutePath()));
		config.add(element("classPath",  element("mainClass", mainClass)));
		
		if (bundleJre) {
			config.add(
					element("jre",  element("path", "jre")
			));
		} else {
			config.add(
					element("jre", element("path", "%JAVA_HOME%")
			));
		}
		
		config.add(
				element("versionInfo", 
				element("fileVersion", "1.0.0.0"),
				element("txtFileVersion", "1.0.0.0"),
				element("copyright", organizationName),
				element("fileDescription", description),
				element("productVersion", version + ".0"),
				element("txtProductVersion", version + ".0"),
				element("productName", name),
				element("internalName", name),
				element("originalFilename", name + ".exe")
		));

		// invokes launch4j plugin to generate windows executable
		executeMojo(
				plugin(
						groupId("com.akathist.maven.plugins.launch4j"), 
						artifactId("launch4j-maven-plugin"),
						version("1.7.25")
				),
				goal("launch4j"),
				configuration(config.toArray(new Element[config.size()])),
				env
			);
	}

	/**
	 * Creates a EXE installer file including all app folder's content only for
	 * Windows so app could be easily distributed
	 * 
	 * @throws MojoExecutionException
	 */
	private void generateWindowsInstaller() throws MojoExecutionException {
		if (!generateInstaller || currentPlatform != Platform.windows) return;

		getLog().info("Generating Windows installer...");

		// copies ico file to assets folder
		FileUtils.copyFileToFolder(iconFile, assetsFolder);
		
		// generates iss file from velocity template
		File issFile = new File(assetsFolder, name + ".iss");
		VelocityUtils.render("windows/iss.vtl", issFile, info);

		// generates windows installer with inno setup command line compiler
		ProcessUtils.execute("iscc", "/O" + outputDirectory.getAbsolutePath(), "/F" + name + "_" + version, issFile);
		
	}

	/**
	 * Creates a DEB package file including all app folder's content only for 
	 * GNU/Linux so app could be easily distributed
	 * 
	 * @throws MojoExecutionException
	 */
	private void generateDebPackage() throws MojoExecutionException {
		if (!generateInstaller || currentPlatform != Platform.linux) return;

		getLog().info("Generating DEB package ...");

		// generates desktop file from velocity template
		File desktopFile = new File(assetsFolder, name + ".desktop");
		VelocityUtils.render("linux/desktop.vtl", desktopFile, info);

		// generates deb control file from velocity template
		File controlFile = new File(assetsFolder, "control");
		VelocityUtils.render("linux/control.vtl", controlFile, info);

		debFile = new File(outputDirectory, name + "_" + version + ".deb");

		// invokes plugin to generate deb package
		executeMojo(
				plugin(
						groupId("org.vafer"), 
						artifactId("jdeb"), 
						version("1.7")
				), 
				goal("jdeb"), 
				configuration(
						element("controlDir", controlFile.getParentFile().getAbsolutePath()),
						element("deb", outputDirectory.getAbsolutePath() + "/${project.name}_${project.version}.deb"),
						element("dataSet",
								/* app folder files, except executable file and jre/bin/java */
								element("data", 
										element("type", "directory"),
										element("src", appFolder.getAbsolutePath()),
										element("mapper", 
												element("type", "perm"),
												element("prefix", "/opt/${project.name}")
										),
										element("excludes", executable.getName() + "," + "jre/bin/java")
								),
								/* executable */
								element("data", 
										element("type", "file"),
										element("src", appFolder.getAbsolutePath() + "/${project.name}"),
										element("mapper", 
												element("type", "perm"), 
												element("filemode", "755"),
												element("prefix", "/opt/${project.name}")
										)
								),
								/* desktop file */
								element("data", 
										element("type", "file"),
										element("src", desktopFile.getAbsolutePath()),
										element("mapper", 
												element("type", "perm"),
												element("prefix", "/usr/share/applications")
										)
								),
								/* java binary file */
								element("data", 
										element("type", "file"),
										element("src", appFolder.getAbsolutePath() + "/jre/bin/java"),
										element("mapper", 
												element("type", "perm"), 
												element("filemode", "755"),
												element("prefix", "/opt/${project.name}/jre/bin")
										)
								),
								/* symbolic link in /usr/local/bin to app binary */
								element("data", 
										element("type", "link"),
										element("linkTarget", "/opt/${project.name}/${project.name}"),
										element("linkName", "/usr/local/bin/${project.name}"),
										element("symlink", "true"), 
										element("mapper", 
												element("type", "perm"),
												element("filemode", "777")
										)
								)
						)
				),
				env);
	}
	
	/**
	 * Creates a DMG image file including all app folder's content only for MacOS so
	 * app could be easily distributed
	 * 
	 * @throws MojoExecutionException
	 */
	private void generateDmgImage() throws MojoExecutionException {
		if (!generateInstaller || currentPlatform != Platform.mac) return;
		
		getLog().info("Generating DMG disk image file");

		// creates a symlink to Applications folder
		File targetFolder = new File("/Applications");
		File linkFile = new File(appFolder, "Applications");
		FileUtils.createSymlink(linkFile, targetFolder);

		// creates the DMG file including app folder's content
		getLog().info("Generating the Disk Image file");
		File diskImageFile = new File(outputDirectory, name + "_" + version + ".dmg");
		ProcessUtils.execute("hdiutil", "create", "-srcfolder", appFolder, "-volname", name, diskImageFile);
		
	}

	/**
	 * Copies all dependencies to app folder
	 * 
	 * @param libsFolder folder containing all dependencies
	 * @throws MojoExecutionException
	 */
	private void copyAllDependencies(File libsFolder) throws MojoExecutionException {
		getLog().info("Copying all dependencies to app folder ...");

		// invokes plugin to copy dependecies to app libs folder
		executeMojo(
				plugin(
						groupId("org.apache.maven.plugins"), 
						artifactId("maven-dependency-plugin"), 
						version("3.1.1")
				),
				goal("copy-dependencies"),
				configuration(
						element("outputDirectory", libsFolder.getAbsolutePath())
				), 
				env);
	}

	/**
	 * Bundle a Java Runtime Enrironment with the app.
	 *
	 * Next link explains the process:
	 * {@link https://medium.com/azulsystems/using-jlink-to-build-java-runtimes-for-non-modular-applications-9568c5e70ef4}
	 *
	 * @throws MojoExecutionException
	 */
	private boolean bundleJre(File jreFolder, File libsFolder) throws MojoExecutionException {
		getLog().info("Bundling JRE ... with " + System.getProperty("java.home"));
		
		if (jrePath != null && !jrePath.isEmpty()) {
			
			getLog().info("Embedding JRE from " + jrePath);
			
			File jrePathFolder = new File(jrePath);

			if (!jrePathFolder.exists()) {
				throw new MojoExecutionException("JRE path specified does not exist: " + jrePath);
			} else if (!jrePathFolder.isDirectory()) {
				throw new MojoExecutionException("JRE path specified is not a folder: " + jrePath);
			}
			
			// removes old jre folder from bundle
			if (jreFolder.exists()) FileUtils.removeFolder(jreFolder);

			// copies JRE folder to bundle
			FileUtils.copyFolderContentToFolder(jrePathFolder, jreFolder);

			// sets execution permissions on executables in jre
			File binFolder = new File(jreFolder, "bin");
			Arrays.asList(binFolder.listFiles()).forEach(f -> f.setExecutable(true, false));

		} else if (JavaUtils.getJavaMajorVersion() <= 8) {
			
			throw new MojoExecutionException("Could not create a customized JRE due to JDK version is " + SystemUtils.JAVA_VERSION + ". Must use jrePath property to specify JRE location to be embedded");
			
		} else if (platform != currentPlatform) {
			
			getLog().warn("Cannot create a customized JRE ... target platform (" + platform + ") is different than execution platform (" + currentPlatform + ")");
			
			info.put("bundleJre", false);
			
			return false;
			
		} else {

			String modules = getRequiredModules(libsFolder);

			getLog().info("Creating JRE with next modules included: " + modules);

			File modulesDir = new File(System.getProperty("java.home"), "jmods");
	
			File jlink = new File(System.getProperty("java.home"), "/bin/jlink");
	
			if (jreFolder.exists()) FileUtils.removeFolder(jreFolder);
			
			// generates customized jre using modules
			ProcessUtils.execute(jlink.getAbsolutePath(), "--module-path", modulesDir, "--add-modules", modules, "--output", jreFolder, "--no-header-files", "--no-man-pages", "--strip-debug", "--compress=2");
	
			// sets execution permissions on executables in jre
			File binFolder = new File(jreFolder, "bin");
			Arrays.asList(binFolder.listFiles()).forEach(f -> f.setExecutable(true, false));

		}
		
		// removes jre/legal folder (needed to codesign command not to fail on macos)
		if (SystemUtils.IS_OS_MAC) {
			File legalFolder = new File(jreFolder, "legal");
			getLog().info("Removing " + legalFolder.getAbsolutePath() + " folder so app could be code signed");
			FileUtils.removeFolder(legalFolder);
		}
		
		return true;
			
	}
	
	/**
	 * Uses jdeps command to determine on which modules depends all used jar files
	 * 
	 * @param libsFolder folder containing all needed libraries
	 * @return strign containing a comma separated list with all needed modules
	 * @throws MojoExecutionException
	 */
	private String getRequiredModules(File libsFolder) throws MojoExecutionException {
		
		getLog().info("Getting required modules ... ");
		
		File jdeps = new File(System.getProperty("java.home"), "/bin/jdeps");

//		File [] jarLibs = libsFolder.listFiles(new FilenameExtensionFilter("jar"));
		File jarLibs = new File(libsFolder, "*.jar");
		
		List<String> modulesList;
		
		if (customizedJre && modules != null && !modules.isEmpty()) {
			
			modulesList = modules
					.stream()
					.map(module -> module.trim())
					.collect(Collectors.toList());
		
		} else if (customizedJre && JavaUtils.getJavaMajorVersion() >= 13) { 
			
			String modules = 
				ProcessUtils.execute(
					jdeps.getAbsolutePath(), 
					"-q",
					"--ignore-missing-deps", 
					"--print-module-deps", 
					"--multi-release", JavaUtils.getJavaMajorVersion(),
					jarLibs.getAbsolutePath(),
					jarFile
				);
			
			modulesList = Arrays.asList(modules.split(","))
					.stream()
					.map(module -> module.trim())
					.collect(Collectors.toList());
			
		} else if (customizedJre && JavaUtils.getJavaMajorVersion() >= 9) { 
		
			String modules = 
				ProcessUtils.execute(
					jdeps.getAbsolutePath(), 
					"-q",
					"--list-deps", 
					"--multi-release", JavaUtils.getJavaMajorVersion(),
					jarLibs.getAbsolutePath(),
					jarFile
				);

			modulesList = Arrays.asList(modules.split("\n"))
					.stream()
					.map(module -> module.trim())
					.filter(module -> !module.startsWith("JDK removed internal"))
					.collect(Collectors.toList());

		} else {
			
			modulesList = Arrays.asList("ALL-MODULE-PATH");
			
		}
				
		modulesList.addAll(additionalModules);
		
		getLog().info("- Modules: " + modulesList);
		
		return StringUtils.join(modulesList, ",");
	}
	
	/**
	 * Copy a list of resources to a folder
	 * 
	 * @param resources   List of files and folders to be copied
	 * @param destination Destination folder. All specified resources will be copied
	 *                    here
	 */
	private void copyAdditionalResources(List<File> resources, File destination) {
		getLog().info("Copying additional resources");
		resources.stream().forEach(r -> {
			if (!r.exists()) {
				getLog().warn("Additional resource " + r + " doesn't exist");
				return;
			}
			try {
				if (r.isDirectory()) {
					FileUtils.copyFolderToFolder(r, destination);
				} else if (r.isFile()) {
					FileUtils.copyFileToFolder(r, destination);
				}
			} catch (MojoExecutionException e) {
				e.printStackTrace();
			}
		});
	}
	
	/**
	 * Returns current platform (Windows, MacOs, GNU/Linux)
	 * 
	 * @return current platform or null if it's not known
	 */
	private Platform getCurrentPlatform() {
		if (SystemUtils.IS_OS_WINDOWS) return Platform.windows;
		if (SystemUtils.IS_OS_LINUX) return Platform.linux;
		if (SystemUtils.IS_OS_MAC_OSX) return Platform.mac;
		return null;
	}

}
