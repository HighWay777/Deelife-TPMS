import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "Pillow"])
    from PIL import Image, ImageDraw, ImageFont

def create_icon(size, is_round=False):
    # Create a dark background
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    padding = size // 8
    
    if is_round:
        draw.ellipse([padding, padding, size-padding, size-padding], fill=(30, 35, 45, 255), outline=(100, 200, 100, 255), width=size//30)
    else:
        radius = size // 6
        draw.rounded_rectangle([padding, padding, size-padding, size-padding], radius=radius, fill=(30, 35, 45, 255), outline=(100, 200, 100, 255), width=size//30)
    
    # Draw text "TPMS"
    try:
        # Try to use a default windows font
        font = ImageFont.truetype("arialbd.ttf", int(size * 0.25))
    except:
        font = ImageFont.load_default()
        
    text = "TPMS"
    # Get text bounding box
    bbox = draw.textbbox((0,0), text, font=font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]
    
    # Draw text in center
    x = (size - text_w) / 2
    y = (size - text_h) / 2 - (size * 0.05) # adjust slightly up
    draw.text((x, y), text, fill=(100, 255, 100, 255), font=font)
    
    return img

def save_icons():
    base_dir = "app/src/main/res"
    sizes = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192
    }
    
    for density, size in sizes.items():
        out_dir = os.path.join(base_dir, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        
        # Normal icon
        img = create_icon(size, is_round=False)
        img.save(os.path.join(out_dir, "ic_launcher.png"))
        
        # Round icon
        img_round = create_icon(size, is_round=True)
        img_round.save(os.path.join(out_dir, "ic_launcher_round.png"))

if __name__ == "__main__":
    save_icons()
    print("Icons generated successfully!")
