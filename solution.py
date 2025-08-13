from album.runner.api import setup


def install():
    import subprocess
    import shutil
    from album.runner.api import get_app_path, get_package_path

    get_app_path().mkdir(exist_ok=True, parents=True)

    # copy source files into solution app folder
    shutil.copy(get_package_path().joinpath('build.gradle'), get_app_path())
    shutil.copy(get_package_path().joinpath('gradlew'), get_app_path())
    shutil.copy(get_package_path().joinpath('gradlew.bat'), get_app_path())
    shutil.copytree(get_package_path().joinpath('src'), get_app_path().joinpath('src'))
    shutil.copytree(get_package_path().joinpath('gradle'), get_app_path().joinpath('gradle'))

    subprocess.run([(get_gradle_executable()), 'build', '-Dorg.gradle.internal.http.socketTimeout=300000'],
                   cwd=get_app_path(), check=True)


def get_gradle_executable():
    from sys import platform
    from album.runner.api import get_app_path
    if platform == "win32":
        return str(get_app_path().joinpath('gradlew.bat').absolute())
    return str(get_app_path().joinpath('gradlew').absolute())


def run():
    import subprocess
    from album.runner.api import get_args, get_app_path
    args = get_args()
    command = [get_gradle_executable(), 'run', '-q', '--args="%s"' % args.ome_zarr_url]
    subprocess.run(command, cwd=get_app_path())


setup(
    group="visualization",
    name="ome-zarr-url-bdv-viewer",
    version="0.1.0",
    solution_creators=["Deborah Schmidt"],
    title="BDV and BVV launcher for OME-ZARR URLs",
    description="This solution opens a OME-ZARR provided via HTTPS URL in BDV and BVV.",
    tags=["bdv", "bvv"],
    cite=[{
        "text": "Pietzsch, T., Saalfeld, S., Preibisch, S., & Tomancak, P. (2015). BigDataViewer: visualization and processing for large image data sets. Nature Methods, 12(6), 481–483.",
        "doi": "10.1038/nmeth.3392"
    }],
    album_api_version="0.5.5",
    args=[{
            "name": "ome_zarr_url",
            "type": "string",
            "required": True,
            "description": "The OME-ZARR url."
        }],
    install=install,
    run=run,
    dependencies={"environment_file": """channels:
  - conda-forge
  - defaults
dependencies:
  - python=3.11
  - requests
  - openjdk=11.0.9.1
"""}
)

