ca# Swords of Newgrange Game

## Tiger Character Implementation

The tiger character has been implemented as a helper companion in solo mode. The implementation uses the tiger.png asset from the assets folder.

### Image Background Transparency

To make the background of tiger.png transparent:

1. If you have Python with Pillow installed:
```python
from PIL import Image

# Open the image
img = Image.open('assets/tiger.png')

# Convert to RGBA if not already
if img.mode != 'RGBA':
    img = img.convert('RGBA')

# Create a mask for the blue background (adjust RGB values as needed)
# Assuming the blue background is around RGB(0, 102, 204) - adjust as needed
data = img.getdata()
new_data = []
for item in data:
    # If pixel is close to blue background, make it transparent
    if abs(item[0] - 0) < 20 and abs(item[1] - 102) < 20 and abs(item[2] - 204) < 20:
        new_data.append((255, 255, 255, 0))  # Make transparent
    else:
        new_data.append(item)

img.putdata(new_data)
img.save('assets/tiger.png', 'PNG')
```

2. Alternatively, you can use an online image editor:
   - Upload tiger.png to an online tool like remove.bg or Photopea
   - Remove the blue background
   - Download and replace the original file

### Implementation Details

The tiger character is displayed using:
- `tigerImage` loaded from `assets/tiger_transparent.png?v=0.4.3`
- Positioned to follow the player character in solo mode
- Animated with walking motion effects
- Maintains all existing game functionality

