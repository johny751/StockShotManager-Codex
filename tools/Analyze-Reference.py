"""Run the preserved analyzer with a reference image from this transfer package."""

import argparse
import runpy
from pathlib import Path


def main():
    package_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", type=Path, default=package_root / "references" / "scr1.png")
    parser.add_argument("--script", type=Path, default=package_root / "StockShotManager" / "_analyze.py")
    args = parser.parse_args()
    image = args.image.resolve()
    script = args.script.resolve()
    if not image.is_file():
        parser.error(f"Reference image does not exist: {image}")
    if not script.is_file():
        parser.error(f"Preserved analyzer does not exist: {script}")
    try:
        namespace = runpy.run_path(str(script), run_name="_stockshot_reference")
    except ModuleNotFoundError as error:
        if error.name == "PIL":
            parser.error("Install Pillow first: python -m pip install Pillow")
        raise
    analyze = namespace["main"]
    analyze.__globals__["PATH"] = str(image)
    analyze()


if __name__ == "__main__":
    main()
